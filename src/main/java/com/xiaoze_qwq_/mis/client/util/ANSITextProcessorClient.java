package com.xiaoze_qwq_.mis.client.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.*;

public class ANSITextProcessorClient {
    private static final int CACHE_SIZE = 200;
    private static final LinkedHashMap<String, Component> CACHE = new LinkedHashMap<String, Component>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private static class StyleState {
        TextColor foregroundColor;
        boolean bold;
        boolean italic;
        boolean underlined;

        StyleState() { reset(); }

        void reset() {
            foregroundColor = null;
            bold = false;
            italic = false;
            underlined = false;
        }

        StyleState copy() {
            StyleState copy = new StyleState();
            copy.foregroundColor = this.foregroundColor;
            copy.bold = this.bold;
            copy.italic = this.italic;
            copy.underlined = this.underlined;
            return copy;
        }

        boolean equals(StyleState other) {
            if (other == null) return false;
            return Objects.equals(foregroundColor, other.foregroundColor) &&
                    bold == other.bold &&
                    italic == other.italic &&
                    underlined == other.underlined;
        }
    }

    private static final String[] ANSI_COLOR_HEX = {
            "#000000", "#AA0000", "#00AA00", "#AA5500",
            "#0000AA", "#AA00AA", "#00AAAA", "#AAAAAA",
            "#555555", "#FF5555", "#55FF55", "#FFFF55",
            "#5555FF", "#FF55FF", "#55FFFF", "#FFFFFF"
    };

    private static String ansiCodeToHex(int code) {
        int index;
        if (code >= 30 && code <= 37) index = code - 30;
        else if (code >= 90 && code <= 97) index = code - 90 + 8;
        else return null;
        return ANSI_COLOR_HEX[index];
    }

    private static TextColor parseColor(String hex) {
        return TextColor.parseColor(hex).result().orElse(null);
    }

    private static void applySgrParameters(StyleState current, List<Integer> params) {
        int i = 0;
        while (i < params.size()) {
            int code = params.get(i);
            if (code == 0) {
                current.reset();
            } else if (code == 1) {
                current.bold = true;
            } else if (code == 3) {
                current.italic = true;
            } else if (code == 4) {
                current.underlined = true;
            } else if (code == 22) {
                current.bold = false;
            } else if (code == 23) {
                current.italic = false;
            } else if (code == 24) {
                current.underlined = false;
            } else if (code == 39) {
                current.foregroundColor = null;
            } else if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) {
                String hex = ansiCodeToHex(code);
                if (hex != null) {
                    current.foregroundColor = parseColor(hex);
                }
            } else if (code == 38) {
                if (i + 1 < params.size()) {
                    int mode = params.get(i + 1);
                    if (mode == 2 && i + 4 < params.size()) {
                        int r = params.get(i + 2);
                        int g = params.get(i + 3);
                        int b = params.get(i + 4);
                        String hex = String.format("#%02X%02X%02X", r, g, b);
                        current.foregroundColor = parseColor(hex);
                        i += 4;
                    } else if (mode == 5 && i + 2 < params.size()) {
                        int colorIndex = params.get(i + 2);
                        if (colorIndex >= 0 && colorIndex < 256) {
                            String hex;
                            if (colorIndex < 16) {
                                hex = ANSI_COLOR_HEX[colorIndex];
                            } else if (colorIndex < 232) {
                                int idx = colorIndex - 16;
                                int r = (idx / 36) * 51;
                                int g = ((idx / 6) % 6) * 51;
                                int b = (idx % 6) * 51;
                                hex = String.format("#%02X%02X%02X", r, g, b);
                            } else {
                                int gray = (colorIndex - 232) * 10 + 8;
                                hex = String.format("#%02X%02X%02X", gray, gray, gray);
                            }
                            current.foregroundColor = parseColor(hex);
                        }
                        i += 2;
                    }
                }
            } else if (code == 48) {
                // 忽略背景色
                if (i + 1 < params.size()) {
                    int mode = params.get(i + 1);
                    if (mode == 2) i += 4;
                    else if (mode == 5) i += 2;
                }
            }
            i++;
        }
    }

    public static Component toTextComponent(String ansiText) {
        if (ansiText == null) return Component.empty();
        synchronized (CACHE) {
            Component cached = CACHE.get(ansiText);
            if (cached != null) return cached;
        }
        Component result = parseInternal(ansiText);
        synchronized (CACHE) {
            CACHE.put(ansiText, result);
        }
        return result;
    }

    private static Component parseInternal(String ansiText) {
        List<MutableComponent> components = new ArrayList<>();
        StyleState current = new StyleState();
        StringBuilder textBuffer = new StringBuilder();
        int index = 0, len = ansiText.length();

        while (index < len) {
            char ch = ansiText.charAt(index);
            if (ch == '\u001B' && index + 1 < len && ansiText.charAt(index + 1) == '[') {
                if (textBuffer.length() > 0) {
                    components.add(createComponent(textBuffer.toString(), current));
                    textBuffer.setLength(0);
                }
                int seqStart = index + 2, cmdEnd = seqStart;
                while (cmdEnd < len && !Character.isLetter(ansiText.charAt(cmdEnd))) cmdEnd++;
                if (cmdEnd < len) {
                    String paramStr = ansiText.substring(seqStart, cmdEnd);
                    char cmd = ansiText.charAt(cmdEnd);
                    index = cmdEnd + 1;
                    if (cmd == 'm') {
                        List<Integer> params = new ArrayList<>();
                        if (!paramStr.isEmpty()) {
                            for (String part : paramStr.split(";")) {
                                if (!part.isEmpty()) {
                                    try {
                                        params.add(Integer.parseInt(part));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                        applySgrParameters(current, params);
                    }
                } else {
                    index++;
                }
            } else {
                textBuffer.append(ch);
                index++;
            }
        }
        if (textBuffer.length() > 0) {
            components.add(createComponent(textBuffer.toString(), current));
        }

        List<MutableComponent> merged = new ArrayList<>();
        for (MutableComponent comp : components) {
            if (!merged.isEmpty() && haveSameStyle(merged.get(merged.size() - 1), comp)) {
                merged.get(merged.size() - 1).append(comp);
            } else {
                merged.add(comp);
            }
        }

        MutableComponent result = Component.empty();
        for (MutableComponent comp : merged) result.append(comp);
        return result;
    }

    private static boolean haveSameStyle(MutableComponent a, MutableComponent b) {
        Style sa = a.getStyle();
        Style sb = b.getStyle();
        return Objects.equals(sa.getColor(), sb.getColor()) &&
                sa.isBold() == sb.isBold() &&
                sa.isItalic() == sb.isItalic() &&
                sa.isUnderlined() == sb.isUnderlined();
    }

    private static MutableComponent createComponent(String text, StyleState style) {
        MutableComponent comp = Component.literal(text);
        Style s = comp.getStyle();
        if (style.foregroundColor != null) s = s.withColor(style.foregroundColor);
        if (style.bold) s = s.withBold(true);
        if (style.italic) s = s.withItalic(true);
        if (style.underlined) s = s.withUnderlined(true);
        comp.setStyle(s);
        return comp;
    }
}