package tg.service.maven;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.GpsDirectory;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping(path = "/v1") // This means URL's start with /v1 (after Application path)
public class WebController {

    @Autowired
    private BlobContainerClient blobContainerClient;


    public String telegramBaseUrl = "https://api.telegram.org/bot";

    @Value("${spring.telegram.token}")
    private String telegramToken;

    @GetMapping("/wahoo-geojson")
    public ResponseEntity<List<RouteGeojson>> getAllWahooDataAsGeojson() {
        Firestore db = FirestoreClient.getFirestore();

// Create a query to retrieve all documents from the "users" collection
        ApiFuture<QuerySnapshot> future = db.collection("wahoo").get();

// Wait for the query to execute and retrieve the documents
        try {
            QuerySnapshot querySnapshot = future.get();
            for (DocumentSnapshot document : querySnapshot.getDocuments()) {
                RouteGeojson routeGeojson = new RouteGeojson();
                //routeGeojson.route = document.getData().get(0).wait();
                System.out.println(document.getId() + " => " + document.getData());
            }
            return null;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }


    }

    @PostMapping(path = "/telegram-webhook")
    public @ResponseBody ResponseEntity receiveTelegramWebhook(@RequestBody TelegramWebhook telegramWebhook) {
        if (telegramWebhook.message == null) {
            return ResponseEntity.notFound().build();
        }
        if (telegramWebhook.message.document != null) {
            // data message (image or wahoo)
            return processDocument(telegramWebhook);
        } else {
            // location message
            saveOnFirebase("locations", telegramWebhook);
            return ResponseEntity.ok().build();

        }
    }

    private void saveAllOnFirebase(ArrayList<FitSequence> fitSequences) {
        int batchSize = 500;
        int index = 0;

        while (index < fitSequences.size()) {
            List<FitSequence> batch = fitSequences.subList(index, Math.min(index + batchSize, fitSequences.size()));
            // Process batch here

            WahooRawRecord wahooRawRecord = new WahooRawRecord();
            wahooRawRecord.fitSequences = batch;

            saveOnFirebase("wh_raw_" + fitSequences.stream().findFirst().get().timestamp, wahooRawRecord);

            index += batchSize;
        }
    }


    private void saveOnFirebase(String collectionName, Object data) {
        try {
            Firestore db = FirestoreClient.getFirestore();

            ApiFuture<DocumentReference> future = db.collection(collectionName).add(data);
            // Wait for the document to be written to the database
            DocumentReference documentReference = future.get();
            System.out.println("Added document with ID: " + documentReference.getId());
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<TelegramWebhook> processDocument(TelegramWebhook telegramWebhook) {
        String file_id = telegramWebhook.message.document.file_id;
        String file_name = telegramWebhook.message.document.file_name;
        String type = telegramWebhook.message.document.file_name.split("\\.")[1];
        if (type.equals("fit")) {
            // file from wahoo /element app

            String file_url = uploadFile(file_name, downloadFile(file_id));
            telegramWebhook.message.document.blob_url = file_url;
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
                    if (latitude != 0.0) {
                        stringBuilder.append("[" + longitude + "," + latitude + "]");
                        if (i < fitSequences.length - 1) {
                            stringBuilder.append(",");
                        }
                    }
                }
                stringBuilder.append("]");
                WahooRecord wahooRecord = new WahooRecord();
                wahooRecord.route = stringBuilder.toString();
                wahooRecord.timeStamp = Arrays.stream(fitSequences).findFirst().get().timestamp;

                saveOnFirebase("wahoo", wahooRecord);
                saveAllOnFirebase(new ArrayList<>(Arrays.asList(fitSequences)));


            } catch (IOException e) {
                throw new RuntimeException(e);
            }


        } else {
            //thumb
            String url = uploadFile("Thumb_" + telegramWebhook.message.document.file_name, downloadFile(telegramWebhook.message.document.thumb.file_id));

            telegramWebhook.message.document.thumb.blob_url = url;
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
                    timestampSec = metadata.getFirstDirectoryOfType(ExifDirectoryBase.class).getDate(306).getTime() / 1000;
                }


                telegramWebhook.message.location = new Location();
                telegramWebhook.message.location.latitude = latitude;
                telegramWebhook.message.location.longitude = longitude;
                telegramWebhook.message.location.created_date = (int) timestampSec;
            } catch (ImageProcessingException | IOException e) {
                throw new RuntimeException(e);
            }

            String image_url = uploadFile(file_name, downloadFile(telegramWebhook.message.document.file_id));

            telegramWebhook.message.document.blob_url = image_url;
        }
        saveOnFirebase("documents", telegramWebhook);

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

    private String uploadFile(String id, BufferedInputStream bufferedInputStream) {
        var blobStorageName = id;
        BlobClient blobClient = blobContainerClient.getBlobClient(blobStorageName);
        var uploadOptions = new BlobParallelUploadOptions(bufferedInputStream);
        var response = blobClient.uploadWithResponse(uploadOptions, Duration.ofSeconds(15),
                Context.NONE);
        if (response.getStatusCode() != HttpStatus.CREATED.value()) {
            System.out.println("ERROR");
        }
        System.out.println("Uploaded file to blob storage");
        var start = OffsetDateTime.now();
        var expiry = start.plusYears(3);
        var permissions = BlobSasPermission.parse("r");
        var sasSignatureValues = new BlobServiceSasSignatureValues(expiry, permissions);
        sasSignatureValues.setStartTime(start);
        var sas = blobClient.generateSas(sasSignatureValues);
        return blobClient.getBlobUrl() + "?" + sas;
    }
}

