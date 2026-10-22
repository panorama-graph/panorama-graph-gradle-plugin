package panoramagraph.gradle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializacao JSON minima (mapas, listas, textos, numeros e booleanos), sem depender da versao do Jackson. */
final class Json {

    private Json() {}

    /** Le um documento JSON: objetos viram {@link LinkedHashMap} (na ordem), listas {@link ArrayList} e numeros {@link BigDecimal}. */
    static Object read(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.skipSpace();
        if (reader.pos != text.length()) {
            throw reader.error("conteudo depois do fim");
        }
        return value;
    }

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

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            skipSpace();
            if (pos >= text.length()) {
                throw error("fim inesperado");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipSpace();
            if (peek('}')) {
                return map;
            }
            do {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                map.put(key, value());
                skipSpace();
            } while (peek(','));
            expect('}');
            return map;
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipSpace();
            if (peek(']')) {
                return list;
            }
            do {
                list.add(value());
                skipSpace();
            } while (peek(','));
            expect(']');
            return list;
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> out.append(escaped);
                }
            }
            throw error("texto sem fim");
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("valor desconhecido");
            }
            pos += word.length();
            return value;
        }

        private BigDecimal number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw error("valor desconhecido");
            }
            return new BigDecimal(text.substring(start, pos));
        }

        private boolean peek(char c) {
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("esperado '" + c + "'");
            }
        }

        void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON invalido na posicao " + pos + ": " + message);
        }
    }
}
