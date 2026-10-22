package panoramagraph.spring.test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Serializacao JSON minima (mapas, listas, textos, numeros e booleanos), sem depender da versao do Jackson. */
final class Json {

    private Json() {}

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeEntries(map.entrySet().iterator(), map.isEmpty(), '{', '}', out, depth, true);
        } else if (value instanceof List<?> list) {
            writeEntries(list.iterator(), list.isEmpty(), '[', ']', out, depth, false);
        } else {
            quote(value.toString(), out);
        }
    }

    private static void writeEntries(
            Iterator<?> items, boolean empty, char open, char close, StringBuilder out, int depth, boolean object) {
        out.append(open);
        if (empty) {
            out.append(close);
            return;
        }
        while (items.hasNext()) {
            Object item = items.next();
            out.append('\n').append("  ".repeat(depth + 1));
            if (object) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
                quote(String.valueOf(entry.getKey()), out);
                out.append(": ");
                write(entry.getValue(), out, depth + 1);
            } else {
                write(item, out, depth + 1);
            }
            if (items.hasNext()) {
                out.append(',');
            }
        }
        out.append('\n').append("  ".repeat(depth)).append(close);
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
