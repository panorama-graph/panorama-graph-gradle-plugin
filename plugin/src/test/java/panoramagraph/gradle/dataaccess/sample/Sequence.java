package panoramagraph.gradle.dataaccess.sample;

import org.springframework.data.mongodb.core.mapping.Document;

@Document
public class Sequence {
    String id;
    long value;
}
