package tg.service.maven;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.GpsDirectory;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.cloud.StorageClient;
import com.google.firebase.database.FirebaseDatabase;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

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

    private ResponseEntity<TelegramWebhook> processDocument(TelegramWebhook telegramWebhook) {
        String file_id = telegramWebhook.message.document.file_id;
        String file_name = telegramWebhook.message.document.file_name;
        String type = telegramWebhook.message.document.file_name.split("\\.")[1];
        Bucket bucket = StorageClient.getInstance().bucket();
        if (type.equals("fit")) {
            // file from wahoo /element app
            Blob blob = bucket.create(file_name, downloadFile(file_id));
            telegramWebhook.message.document.blob_id = blob.getBlobId().getName();
            //parse
            okhttp3.ResponseBody responseBody = parseFit(downloadFile(file_id));
            Gson gson = new Gson();
            List<double[]> coordinates = new ArrayList<>();
            try {
                FitSequence[] fitSequences = gson.fromJson(responseBody.string(), FitSequence[].class);
                double factor = (180 / Math.pow(2, 31));
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("[ ");
                for (int i = 0; i < fitSequences.length; i++) {
                    double latitude = fitSequences[i].position_lat * factor;
                    double longitude = fitSequences[i].position_long * factor;
                    coordinates.add(new double[]{latitude, longitude});
                    if (longitude != 0.0) {
                        stringBuilder.append("[" + latitude + "," + longitude + "]");
                        if (i < fitSequences.length - 1) {
                            stringBuilder.append(",");
                        }
                    }
                }
                stringBuilder.append("]");
                FirebaseDatabase.getInstance().getReference().child("wahoo").child(Arrays.stream(fitSequences).findFirst().get().timestamp).setValueAsync(stringBuilder.toString());


            } catch (IOException e) {
                throw new RuntimeException(e);
            }


        } else {
            //thumb
            Blob thumbImageBlob = bucket.create("THUMB_" + file_name, downloadFile(telegramWebhook.message.document.thumb.file_id), "image/jpeg");

            telegramWebhook.message.document.thumb.blob_id = thumbImageBlob.getBlobId().getName();
            //image
            BufferedInputStream originalImage = downloadFile(telegramWebhook.message.document.file_id);

            try {
                Metadata metadata = ImageMetadataReader.readMetadata(originalImage);
                GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);

                double latitude = gpsDirectory.getGeoLocation().getLatitude();
                double longitude = gpsDirectory.getGeoLocation().getLongitude();
                long timestampSec;
                if (gpsDirectory.getGpsDate() != null) {
                    timestampSec = gpsDirectory.getGpsDate().getTime() / 1000;
                } else {
                    timestampSec = metadata.getFirstDirectoryOfType(ExifDirectoryBase.class).getDate(306).getTime();
                }


                telegramWebhook.message.location = new Location();
                telegramWebhook.message.location.latitude = latitude;
                telegramWebhook.message.location.longitude = longitude;
                telegramWebhook.message.location.created_date = (int) timestampSec;
            } catch (ImageProcessingException | IOException e) {
                throw new RuntimeException(e);
            }

            Blob returnedImage = bucket.create(file_name, downloadFile(telegramWebhook.message.document.file_id), "image/jpeg");

            telegramWebhook.message.document.blob_id = returnedImage.getBlobId().getName();
        }
        final FirebaseDatabase database = FirebaseDatabase.getInstance();
        database.getReference().child("documents").child(UUID.randomUUID().toString()).setValueAsync(telegramWebhook);

        return ResponseEntity.ok(telegramWebhook);

    }

    private okhttp3.ResponseBody parseFit(BufferedInputStream bufferedInputStream) {
        OkHttpClient client = new OkHttpClient().newBuilder()
                .build();
        MediaType mediaType = MediaType.parse("application/octet-stream");

        try {
            okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(bufferedInputStream.readAllBytes());
            Request request = new Request.Builder()
                    .url("http://127.0.0.1:5000/parse")
                    .method("POST", requestBody)
                    .addHeader("Content-Type", "application/octet-stream")
                    .build();
            Response response = client.newCall(request).execute();
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private BufferedInputStream downloadFile(String file_id) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(telegramBaseUrl + telegramToken + "/getFile?file_id=" + file_id)
                .build();
        Call call = client.newCall(request);
        Response response = null;

        try {
            response = call.execute();
            String body = response.body().string();
            String file_path = JsonParser.parseString(body).getAsJsonObject().get("result").getAsJsonObject().get("file_path").getAsString();

            URL url = new URL("https://api.telegram.org/file/bot" + telegramToken + "/" + file_path);
            return new BufferedInputStream(url.openStream());

        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}

