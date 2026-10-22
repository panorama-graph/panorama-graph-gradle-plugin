package panoramagraph.gradle.dataaccess;

import java.util.ArrayList;
import java.util.List;

/** Leitura minima de assinaturas genericas de classe (JVMS 4.7.9.1): so o que o analisador precisa. */
final class Signatures {

    private Signatures() {}

    /**
     * Superclasse e interfaces de uma assinatura de classe, cada uma como {@code [nome interno, primeiro argumento de
     * tipo]}. O argumento e null quando nao ha, ou quando nao e uma classe concreta (variavel de tipo, curinga).
     * {@code Ljava/lang/Object;Lorg/x/MongoRepository<Lorg/x/Foo;Ljava/lang/String;>;} da
     * {@code [[java/lang/Object, null], [org/x/MongoRepository, org/x/Foo]]}.
     */
    static List<String[]> superTypes(String signature) {
        List<String[]> out = new ArrayList<>();
        int i = 0;
        if (signature.charAt(0) == '<') {
            i = skipBrackets(signature, 0);
        }
        while (i < signature.length() && signature.charAt(i) == 'L') {
            int[] end = new int[1];
            String[] type = classType(signature, i, end);
            out.add(type);
            i = end[0];
        }
        return out;
    }

    /** Le {@code Lnome<args>;} a partir de {@code start}; devolve [nome, primeiro argumento] e o fim em {@code end}. */
    private static String[] classType(String s, int start, int[] end) {
        int i = start + 1;
        StringBuilder name = new StringBuilder();
        String firstArg = null;
        while (s.charAt(i) != ';') {
            char c = s.charAt(i);
            if (c == '<') {
                int argStart = i + 1;
                if (s.charAt(argStart) == 'L') {
                    int[] argEnd = new int[1];
                    firstArg = classType(s, argStart, argEnd)[0];
                }
                i = skipBrackets(s, i);
            } else {
                // tipo interno (Outer<A>.Inner): o nome segue com '$'
                name.append(c == '.' ? '$' : c);
                i++;
            }
        }
        end[0] = i + 1;
        return new String[] {name.toString(), firstArg};
    }

    /** Posicao depois do '>' que fecha o '<' em {@code open}. */
    private static int skipBrackets(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        throw new IllegalArgumentException("assinatura malformada: " + s);
    }
}
