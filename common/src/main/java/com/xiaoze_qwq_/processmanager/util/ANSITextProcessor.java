package com.xiaoze_qwq_.processmanager.util;

import java.util.regex.Pattern;

public class ANSITextProcessor {
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");

    public static String toPlainText(String ansiString) {
        if (ansiString == null) return "";
        return ANSI_PATTERN.matcher(ansiString).replaceAll("");
    }
}