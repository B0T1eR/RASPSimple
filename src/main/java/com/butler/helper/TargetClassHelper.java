package com.butler.helper;

public class TargetClassHelper {
    public static final String PROCESSIMPL = "java/lang/ProcessImpl";
    public static final String UNIXPROCESS = "java/lang/UNIXProcess";

    public static boolean isTargetClass(String className) {
        return PROCESSIMPL.replace("/",".").equals(className) || UNIXPROCESS.replace("/",".").equals(className);
    }
}
