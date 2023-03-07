package tg.service.maven;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import com.google.api.core.ApiFuture;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.cloud.StorageClient;
import com.google.firebase.database.FirebaseDatabase;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.UUID;

@RestController
@RequestMapping(path = "/v1") // This means URL's start with /v1 (after Application path)
public class WebController {

    public String telegramBaseUrl = "https://api.telegram.org/bot";

    @Value("${spring.telegram.token}")
    private String telegramToken;

    @PostMapping(path = "/telegram-webhook")
    public @ResponseBody ResponseEntity receiveTelegramWebhook(@RequestBody TelegramWebhook telegramWebhook) {
        if (telegramWebhook.message == null) {
            return ResponseEntity.notFound().build();
        }
        if (telegramWebhook.message.document != null) {
            return processDocument(telegramWebhook);
        } else {
            final FirebaseDatabase database = FirebaseDatabase.getInstance();
            database.getReference().child(UUID.randomUUID().toString()).setValueAsync(telegramWebhook);
            return ResponseEntity.ok().build();
        }
    }

    private ResponseEntity processDocument(TelegramWebhook telegramWebhook) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(telegramBaseUrl + telegramToken + "/getFile?file_id=" + telegramWebhook.message.document.file_id)
                .build();

        Call call = client.newCall(request);
        Response response = null;
        try {
            response = call.execute();
            String body = response.body().string();

            String file_path = JsonParser.parseString(body).getAsJsonObject().get("result").getAsJsonObject().get("file_path").getAsString();

            Bucket bucket = StorageClient.getInstance().bucket();
            InputStream inputStream;
            try {
                URL url = new URL("https://api.telegram.org/file/bot" + telegramToken + "/" + file_path);
                inputStream = new BufferedInputStream(url.openStream());

                var fileName = file_path.split("/")[1];
                var type = fileName.split("\\.")[1];
                if (type.equals("fit")) {
                    // file from wahoo /element app
                    bucket.create(fileName, inputStream);
                } else {
                    //image
                    Blob returnedImage = bucket.create(fileName, inputStream, "image/jpeg");

                    Metadata metadata = ImageMetadataReader.readMetadata(new BufferedInputStream(url.openStream()));
                    GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);

                    double latitude = gpsDirectory.getGeoLocation().getLatitude();
                    double longitude = gpsDirectory.getGeoLocation().getLongitude();
                    long timestampSec = gpsDirectory.getGpsDate().getTime() / 1000;

                    telegramWebhook.message.location = new Location();
                    telegramWebhook.message.location.latitude = latitude;
                    telegramWebhook.message.location.longitude = longitude;
                    telegramWebhook.message.location.created_date = (int) timestampSec;
                    telegramWebhook.message.document.blob_id = returnedImage.getBlobId().getName();
                }
                final FirebaseDatabase database = FirebaseDatabase.getInstance();
                database.getReference().child(UUID.randomUUID().toString()).setValueAsync(telegramWebhook);

                return ResponseEntity.ok().build();

            } catch (IOException e) {
                throw new RuntimeException(e.getMessage());
            } catch (ImageProcessingException e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            //TODO
            throw new RuntimeException(e);
        }
    }
}
