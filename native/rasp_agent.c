#include <stdio.h>
#include <string.h>
#include <jni.h>
#include <jvmti.h>
#include <stdarg.h>
#include <time.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#endif

/*
 * 简化示例：在 NativeMethodBind 时替换 ProcessImpl.create 和 UNIXProcess.forkAndExec 的地址为自定义实现
 * 检测危险命令并阻断（返回 -1）。
 */

/* 全局 JVMTI 指针 */
static jvmtiEnv *g_jvmti = NULL;

/* 保存原始函数地址（第一次绑定时保存） */
static void *g_orig_ProcessImpl_create = NULL;
static void *g_orig_UNIXProcess_forkAndExec = NULL;

/* ProcessImpl.create 原型：与 JNIEXPORT jlong JNICALL Java_java_lang_ProcessImpl_create(...) 匹配 */
typedef jlong (JNICALL *orig_Create_fn)(
        JNIEnv *env, jclass ignored,
        jstring cmd, jstring envBlock,
        jstring dir, jlongArray stdHandles,
        jboolean redirectErrorStream);

/* UNIXProcess.forkAndExec 原型：假定返回 jint（PID 或错误码），请根据目标 JDK 调整为 jlong 等 */
/* 正确的原始函数 typedef（与 Java_java_lang_UNIXProcess_forkAndExec 对应） */
typedef jint (JNICALL *orig_forkAndExec_fn)(
        JNIEnv *env,
        jobject process,      /* instance method */
        jint someInt,         /* first param: I */
        jbyteArray ba1,       /* [B */
        jbyteArray ba2,       /* [B */
        jbyteArray ba3,       /* [B (第三个 byte[]，实际含义请用下方调试确认) */
        jint i1,              /* I */
        jbyteArray ba4,       /* [B */
        jint i2,              /* I */
        jbyteArray ba5,       /* [B */
        jintArray ia1,        /* [I */
        jboolean z1           /* Z */
);

static FILE *g_log_file = NULL;
#ifdef _WIN32
static CRITICAL_SECTION log_lock;
#else
static pthread_mutex_t log_lock = PTHREAD_MUTEX_INITIALIZER;
#endif

static void rasplog_init_once() {
    if (g_log_file == NULL) {
        // 获取当前日期
        time_t t = time(NULL);
        struct tm tm_info;
#ifdef _WIN32
        localtime_s(&tm_info, &t);
#else
        localtime_r(&t, &tm_info);
#endif

        // 文件名：RASPSimple_Native_YYYYMMDD.log
        char filename[256];
        strftime(filename, sizeof(filename),
                 "RASPSimple_Native_%Y%m%d.log", &tm_info);

        g_log_file = fopen(filename, "a");
        if (g_log_file == NULL) {
            fprintf(stderr, "[RASPSimple] Failed to open log file %s\n", filename);
        } else {
            fprintf(stderr, "[RASPSimple] Log file initialized: %s\n", filename);
            fflush(stderr);
        }

#ifdef _WIN32
        InitializeCriticalSection(&log_lock);
#endif
    }
}

static void rasplog(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);

    char msg[1024];
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    // 生成时间戳
    time_t t = time(NULL);
    struct tm tm_info;
    char timebuf[64];
#ifdef _WIN32
    localtime_s(&tm_info, &t);
#else
    localtime_r(&t, &tm_info);
#endif
    strftime(timebuf, sizeof(timebuf), "%Y-%m-%d %H:%M:%S", &tm_info);

    rasplog_init_once();

#ifdef _WIN32
    EnterCriticalSection(&log_lock);
#else
    pthread_mutex_lock(&log_lock);
#endif

    if (g_log_file != NULL) {
        fprintf(g_log_file, "[%s] [RASPSimple] %s\n", timebuf, msg);
        fflush(g_log_file);
    }
    // 同时输出到控制台
    fprintf(stderr, "[%s] [RASPSimple] %s\n", timebuf, msg);
    fflush(stderr);

#ifdef _WIN32
    LeaveCriticalSection(&log_lock);
#else
    pthread_mutex_unlock(&log_lock);
#endif
}

static const char *BLACKLIST_KEYWORDS[] = {
        "cat",
        "type",
        "nc",
        "ncat",
        "netcat",
        "python",
        "powershell",
        "perl",
        "curl",
        "wget"
};

static int matches_blacklist_simple(const char *cmd) {
    if (!cmd) return 0;
    for (int i = 0; i < sizeof(BLACKLIST_KEYWORDS)/sizeof(BLACKLIST_KEYWORDS[0]); i++) {
        const char *pat = BLACKLIST_KEYWORDS[i];
#ifdef _WIN32
        /* Windows 没有 strcasestr，用 _stricmp+strstr 模拟 */
        char lower_cmd[4096];
        char lower_pat[256];
        strncpy(lower_cmd, cmd, sizeof(lower_cmd)-1);
        strncpy(lower_pat, pat, sizeof(lower_pat)-1);
        _strlwr(lower_cmd);
        _strlwr(lower_pat);
        if (strstr(lower_cmd, lower_pat)) return 1;
#else
        if (strcasestr(cmd, pat)) return 1;
#endif
    }
    return 0;
}

static void print_jvmti_stack_trace(JNIEnv *env) {
    if (g_jvmti == NULL) {
        rasplog("print_jvmti_stack_trace: g_jvmti is NULL");
        return;
    }

    const jint max_frames = 64;
    jvmtiFrameInfo frames[max_frames];
    jint count = 0;
    jvmtiError err;

    /* 获取当前线程栈（在 native 回调的线程上下文中，传 NULL 表示当前线程） */
    err = (*g_jvmti)->GetStackTrace(g_jvmti, NULL /* current thread */, 0 /* start_depth */, max_frames, frames, &count);
    if (err != JVMTI_ERROR_NONE) {
        rasplog("GetStackTrace failed: %d", err);
        return;
    }

    rasplog("=== JVMTI StackTrace (frames=%d) ===", count);
    for (int i = 0; i < count; ++i) {
        char *methodName = NULL;
        char *methodSig = NULL;
        char *classSig = NULL;
        jclass declClass = NULL;

        if (frames[i].method == NULL) {
            rasplog("#%d <unknown method> (location=%lld)", i, (long long)frames[i].location);
            continue;
        }

        err = (*g_jvmti)->GetMethodName(g_jvmti, frames[i].method, &methodName, &methodSig, NULL);
        if (err != JVMTI_ERROR_NONE) {
            rasplog("#%d GetMethodName error: %d", i, err);
            continue;
        }

        err = (*g_jvmti)->GetMethodDeclaringClass(g_jvmti, frames[i].method, &declClass);
        if (err != JVMTI_ERROR_NONE) {
            declClass = NULL;
        }
        if (declClass != NULL) {
            err = (*g_jvmti)->GetClassSignature(g_jvmti, declClass, &classSig, NULL);
            if (err != JVMTI_ERROR_NONE) {
                classSig = NULL;
            }
        }

        rasplog("#%d %s.%s %s (location=%lld)", i,
                classSig ? classSig : "<no-class>",
                methodName ? methodName : "<no-method>",
                methodSig ? methodSig : "",
                (long long)frames[i].location);

        if (methodName) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)methodName);
        if (methodSig)  (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)methodSig);
        if (classSig)   (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)classSig);
    }
    rasplog("=== End StackTrace ===");
}

/* Hook 函数：ProcessImpl.create（保留你原来的实现，略微格式化） */
static jlong JNICALL My_ProcessImpl_create(
        JNIEnv *env, jclass ignored,
        jstring cmd, jstring envBlock,
        jstring dir, jlongArray stdHandles,
        jboolean redirectErrorStream)
{
    const char *cmd_utf = NULL;
    if (cmd != NULL) {
        cmd_utf = (*env)->GetStringUTFChars(env, cmd, NULL);
    }

    if (cmd_utf) {
        rasplog("Intercepted ProcessImpl.create cmd='%s'", cmd_utf);

        if (matches_blacklist_simple(cmd_utf)) {

            time_t t = time(NULL);
            struct tm tm_info;
#ifdef _WIN32
            localtime_s(&tm_info, &t);
#else
            localtime_r(&t, &tm_info);
#endif
            char timebuf[64];
            strftime(timebuf, sizeof(timebuf), "%Y-%m-%d %H:%M:%S", &tm_info);

            rasplog("%s Blocked dangerous command at: %s", timebuf, cmd_utf);
            print_jvmti_stack_trace(env);

            (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
            return (jlong)-1;
        }

        (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
    } else {
        rasplog("Intercepted ProcessImpl.create but cmd is NULL");
    }

    if (g_orig_ProcessImpl_create != NULL) {
        orig_Create_fn orig = (orig_Create_fn)g_orig_ProcessImpl_create;
        return orig(env, ignored, cmd, envBlock, dir, stdHandles, redirectErrorStream);
    } else {
        rasplog("Original function pointer not found - blocking by default");
        return (jlong)-1;
    }
}

/* Hook 函数：UNIXProcess.forkAndExec */
/* Hook 函数：UNIXProcess.forkAndExec（修正签名） */
static jint JNICALL My_UNIXProcess_forkAndExec(
        JNIEnv *env,
        jobject process,
        jint someInt,
        jbyteArray ba1,   // helperpath
        jbyteArray ba2,   // cprog
        jbyteArray ba3,   // argBlock  <-- 真实命令
        jint i1,          // argc
        jbyteArray ba4,   // envBlock
        jint i2,          // envc
        jbyteArray ba5,   // cwd
        jintArray ia1,
        jboolean z1)
{
    char helper_buf[256] = {0};
    char prog_buf[256]   = {0};
    char args_buf[2048]  = {0};

    // ba1: helperpath
    if (ba1 != NULL) {
        jsize len = (*env)->GetArrayLength(env, ba1);
        if (len > 0) {
            jsize copy = len < sizeof(helper_buf)-1 ? len : sizeof(helper_buf)-1;
            jbyte *pbytes = (*env)->GetByteArrayElements(env, ba1, NULL);
            memcpy(helper_buf, pbytes, copy);
            helper_buf[copy] = '\0';
            (*env)->ReleaseByteArrayElements(env, ba1, pbytes, JNI_ABORT);
        }
    }

    // ba2: cprog (e.g. "/bin/sh")
    if (ba2 != NULL) {
        jsize len = (*env)->GetArrayLength(env, ba2);
        if (len > 0) {
            jsize copy = len < sizeof(prog_buf)-1 ? len : sizeof(prog_buf)-1;
            jbyte *pbytes = (*env)->GetByteArrayElements(env, ba2, NULL);
            memcpy(prog_buf, pbytes, copy);
            prog_buf[copy] = '\0';
            (*env)->ReleaseByteArrayElements(env, ba2, pbytes, JNI_ABORT);
        }
    }

    // ba3: argBlock (real arguments, e.g. "-c whoami")
    if (ba3 != NULL) {
        jsize len = (*env)->GetArrayLength(env, ba3);
        if (len > 0) {
            jsize copy = len < sizeof(args_buf)-1 ? len : sizeof(args_buf)-1;
            jbyte *abytes = (*env)->GetByteArrayElements(env, ba3, NULL);
            // translate nulls to spaces for readability
            for (jsize i = 0, j = 0; i < copy; ++i) {
                char c = (char)abytes[i];
                if (c == '\0' || c == '\n' || c == '\r' || c == '\t') {
                    if (j < sizeof(args_buf)-1) args_buf[j++] = ' ';
                } else {
                    if (j < sizeof(args_buf)-1) args_buf[j++] = c;
                }
            }
            args_buf[sizeof(args_buf)-1] = '\0';
            (*env)->ReleaseByteArrayElements(env, ba3, abytes, JNI_ABORT);
        }
    }

    // Combined
    char combined[4096];
    snprintf(combined, sizeof(combined), "%s %s %s",
             helper_buf[0] ? helper_buf : "(null)",
             prog_buf[0] ? prog_buf : "(null)",
             args_buf[0] ? args_buf : "(null)");

    rasplog("Intercepted UNIXProcess.forkAndExec someInt=%d helper='%s' prog='%s' args='%s'",
            (int)someInt, helper_buf, prog_buf, args_buf);

    // ⚠️ 检查命令关键字（针对 args_buf）
    if (matches_blacklist_simple(args_buf))
    {
        rasplog("Blocked dangerous command: %s", args_buf);
        print_jvmti_stack_trace(env);
        return (jint)-1;
    }

    // 调用原始
    if (g_orig_UNIXProcess_forkAndExec != NULL) {
        orig_forkAndExec_fn orig = (orig_forkAndExec_fn)g_orig_UNIXProcess_forkAndExec;
        return orig(env, process, someInt, ba1, ba2, ba3, i1, ba4, i2, ba5, ia1, z1);
    } else {
        rasplog("Original UNIXProcess.forkAndExec pointer not found - blocking");
        return (jint)-1;
    }
}

/* JVMTI NativeMethodBind 回调：在 native 方法被绑定时触发 */
void JNICALL cbNativeMethodBind(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread,
        jmethodID method,
        void* address,
        void** new_address)
{
    char *methodName = NULL;
    char *methodSig = NULL;
    char *classSig = NULL;
    jclass declClass = NULL;

    if (g_jvmti == NULL) return;

    jvmtiError err;

    err = (*g_jvmti)->GetMethodName(g_jvmti, method, &methodName, &methodSig, NULL);
    if (err != JVMTI_ERROR_NONE) {
        methodName = NULL;
        methodSig = NULL;
    }
    err = (*g_jvmti)->GetMethodDeclaringClass(g_jvmti, method, &declClass);
    if (err != JVMTI_ERROR_NONE) {
        declClass = NULL;
    }
    if (declClass != NULL) {
        (*g_jvmti)->GetClassSignature(g_jvmti, declClass, &classSig, NULL);
    }

    if (methodName && classSig) {
        /* 寻找 java/lang/ProcessImpl.create 方法 */
        if (strcmp(methodName, "create") == 0 &&
            strstr(classSig, "java/lang/ProcessImpl") != NULL) {

            rasplog("NativeMethodBind: hooking %s %s", classSig, methodName);

            if (g_orig_ProcessImpl_create == NULL) {
                g_orig_ProcessImpl_create = address;
                rasplog("Saved original ProcessImpl.create address %p", address);
            }

            *new_address = (void*)&My_ProcessImpl_create;
        }

        /* 寻找 java/lang/UNIXProcess.forkAndExec 方法 */
        if (strcmp(methodName, "forkAndExec") == 0 &&
            strstr(classSig, "java/lang/UNIXProcess") != NULL) {

            rasplog("NativeMethodBind: hooking %s %s", classSig, methodName);

            if (g_orig_UNIXProcess_forkAndExec == NULL) {
                g_orig_UNIXProcess_forkAndExec = address;
                rasplog("Saved original UNIXProcess.forkAndExec address %p", address);
            }

            *new_address = (void*)&My_UNIXProcess_forkAndExec;
        }
    }

    if (methodName) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)methodName);
    if (methodSig)  (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)methodSig);
    if (classSig)   (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)classSig);
}

/* Agent_OnAttach 应用启动后Attach */
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    return Agent_OnLoad(vm, options, reserved);
}

/* Agent_OnLoad：设置 JVMTI、注册回调 和应用同时启动时候使用Agent_OnLoad */
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jint rc;
    jvmtiError err;
    jvmtiCapabilities caps;
    jvmtiEventCallbacks callbacks;
    rasplog("Agent_OnLoad called");

    rc = (*vm)->GetEnv(vm, (void**)&g_jvmti, JVMTI_VERSION_1_2);
    if (rc != JNI_OK || g_jvmti == NULL) {
        rasplog("Unable to get JVMTI env, rc=%d", rc);
        return JNI_ERR;
    }

    memset(&caps, 0, sizeof(caps));
    caps.can_generate_native_method_bind_events = 1;
    err = (*g_jvmti)->AddCapabilities(g_jvmti, &caps);
    if (err != JVMTI_ERROR_NONE) {
        rasplog("AddCapabilities failed: %d", err);
    }

    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.NativeMethodBind = &cbNativeMethodBind;
    err = (*g_jvmti)->SetEventCallbacks(g_jvmti, &callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        rasplog("SetEventCallbacks failed: %d", err);
        return JNI_ERR;
    }

    err = (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE, JVMTI_EVENT_NATIVE_METHOD_BIND, NULL);
    if (err != JVMTI_ERROR_NONE) {
        rasplog("SetEventNotificationMode failed: %d", err);
    }

    rasplog("JVMTI agent loaded OK");
    return JNI_OK;
}

/* Agent_OnUnload 清理 */
JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    rasplog("Agent_OnUnload called");
    g_orig_ProcessImpl_create = NULL;
    g_orig_UNIXProcess_forkAndExec = NULL;
    g_jvmti = NULL;
}