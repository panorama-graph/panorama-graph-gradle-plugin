package panoramagraph.gradle.dataaccess.sample;

import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "#{@tenant.prefix}_audit")
public class Audit {
    String id;
}
