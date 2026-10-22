package panoramagraph.gradle.dataaccess.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class LineItem {
    @Id
    Long id;
}
