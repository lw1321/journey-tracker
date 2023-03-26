package tg.service.maven.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;


@Configuration
@ConfigurationProperties(prefix = "firebase")
public class FirebaseConfig {
    @Value("${spring.firebase.credentials}")
    private String credentials;
    @Bean
    FirebaseApp createFireBaseApp() throws IOException {
        ByteArrayInputStream serviceAccount = new ByteArrayInputStream(credentials.getBytes(StandardCharsets.UTF_8));
        FirebaseOptions options = null;
        try {
            options = new FirebaseOptions.Builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .setDatabaseUrl("https://find-me-75cd0-default-rtdb.europe-west1.firebasedatabase.app")
                    .setStorageBucket("find-me-75cd0.appspot.com")
                    .build();
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

       return FirebaseApp.initializeApp(options);


    }

}
