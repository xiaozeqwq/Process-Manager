package com.xiaoze_qwq_.processmanager.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 将命令行字符串拆分为参数列表，支持单引号、双引号包裹含空格的参数，
 * 以及双引号内的反斜杠转义。
 */
public final class CommandLineTokenizer {
    private CommandLineTokenizer() {
    }

    public static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        if (command == null) return tokens;
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean tokenStarted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                else current.append(c);
            } else if (inDouble) {
                if (c == '"') inDouble = false;
                else if (c == '\\' && i + 1 < command.length()) current.append(command.charAt(++i));
                else current.append(c);
            } else if (c == '\'') {
                inSingle = true;
                tokenStarted = true;
            } else if (c == '"') {
                inDouble = true;
                tokenStarted = true;
            } else if (Character.isWhitespace(c)) {
                if (tokenStarted || current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(c);
            }
        }
        if (tokenStarted || current.length() > 0) tokens.add(current.toString());
        return tokens;
    }
}
