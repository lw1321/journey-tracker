package tg.service.maven.endpoints;




import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.ClassPathResource;

import java.util.stream.Collectors;
import java.text.SimpleDateFormat;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.GpsDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
import tg.service.maven.models.*;
import tg.service.maven.models.telegram.Location;
import tg.service.maven.models.telegram.TelegramWebhook;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;


@RestController
@RequestMapping(path = "/v1") // This means URL's start with /v1 (after Application path)
public class WebController {

    @Autowired
    private BlobContainerClient blobContainerClient;

    @Autowired
    private ObjectMapper objectMapper;

    public String telegramBaseUrl = "https://api.telegram.org/bot";

    @Value("${spring.telegram.token}")
    private String telegramToken;

    private Cache<String, List<RouteGeojson>> wahooCache = CacheBuilder
            .newBuilder()
            .maximumSize(1)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();


    @GetMapping("/")
    public String index() {
        return "index.html";
    }

    @GetMapping("/wahoo-raw/meta")
    public List<String> wahooMetadata() throws InterruptedException, ExecutionException {
        Firestore db = FirestoreClient.getFirestore();

        // Create a query to retrieve all documents from the "wahoo-raw" collection
        ApiFuture<QuerySnapshot> future = db.collection("wahoo-raw").get();

        // Wait for the query to execute and retrieve the documents
        QuerySnapshot querySnapshot = future.get();

        // Create a list to store document names
        List<String> documentNames = new ArrayList<>();

        // Iterate through the query results and get the document names
        for (DocumentSnapshot document : querySnapshot.getDocuments()) {
            documentNames.add(document.getId());
        }

        return documentNames;
    }
    @GetMapping("/wahoo-raw/{documentId}")
    public Map<String, Object> getDocumentData(@PathVariable String documentId)
            throws InterruptedException, ExecutionException {
        Firestore db = FirestoreClient.getFirestore();
        DocumentReference docRef = db.collection("wahoo-raw").document(documentId);
        ApiFuture<DocumentSnapshot> future = docRef.get();
        DocumentSnapshot document = future.get();

        if (document.exists()) {
            return document.getData();
        } else {
            // Handle the case where the document does not exist
            throw new DocumentNotFoundException("Document with ID " + documentId + " not found.");
        }
    }

    

    @GetMapping("/route/map")
    public ResponseEntity<Map<String, Double>> getMapOfDateAndDistancePerDay() throws Exception {
System.out.println("hui");
        Firestore db = FirestoreClient.getFirestore();

        // Create a query to retrieve all documents from the "wahoo" collection
        ApiFuture<QuerySnapshot> future = db.collection("wahoo").get();

        // Wait for the query to execute and retrieve the documents
        QuerySnapshot querySnapshot = future.get();

        // Create a map to store date as key and total distance as value
        Map<String, Double> dateDistanceMap = new HashMap<>();

        // Iterate through each document and calculate total distance per day
        for (QueryDocumentSnapshot document : querySnapshot) {
            if (document.contains("blobUrl")) {
                String blobUrl = document.getString("blobUrl");
                if (blobUrl != null) {
                    var routeJson = document.getData().get("route").toString();
                    List<List<Double>> route = objectMapper.readValue(routeJson, List.class);
                    double distance = calculateDistance(route);

                    // Get the timestamp and convert it to date
                    String timestamp = document.getData().get("timeStamp").toString();
                    String date = convertTimestampToDate(timestamp);


                    // Add the calculated distance to the existing distance for the date
                    dateDistanceMap.put(date, dateDistanceMap.getOrDefault(date, 0.0) + distance);
                }
            }
        }

Map<String, Double> sortedMap = sortMapByDate(dateDistanceMap);
        return ResponseEntity.ok(sortedMap);
    }
private Map<String, Double> sortMapByDate(Map<String, Double> unsortedMap) {
        return unsortedMap.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
    }
    

private String convertTimestampToDate(String timestamp) {
    SimpleDateFormat inputDateFormat = new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss");

    try {
        Date date = inputDateFormat.parse(timestamp);
SimpleDateFormat dateFormat = new SimpleDateFormat("MM.dd.yyyy");
    return dateFormat.format(date);
    } catch (Exception e) {
        throw new RuntimeException("Error parsing date: " + e.getMessage());
    }
}

@GetMapping("/route/distance-bike")
public double calculateBikeDistance() throws Exception {
    Firestore db = FirestoreClient.getFirestore();

    // Create a query to retrieve all documents from the "wahoo" collection
    ApiFuture<QuerySnapshot> future = db.collection("wahoo").get();

    // Wait for the query to execute and retrieve the documents
    var response = new ArrayList<RouteGeojson>();
    QuerySnapshot querySnapshot = future.get();

    // Iterate through each document and calculate total distance
    double totalDistance = 0.0;
    for (QueryDocumentSnapshot document : querySnapshot) {
        if (document.contains("blobUrl")) {
            String blobUrl = document.getString("blobUrl");
            if (blobUrl != null) {
                var routeJson = document.getData().get("route").toString();
                List<List<Double>> route = objectMapper.readValue(routeJson, List.class);
                totalDistance += calculateDistance(route);
            }
        }
    }

    return totalDistance;
}



    @GetMapping("/route/distance")
    public double calculateTotalDistance() throws Exception {
        
        
Firestore db = FirestoreClient.getFirestore();

                // Create a query to retrieve all documents from the "users" collection
                ApiFuture<QuerySnapshot> future = db.collection("wahoo").get();

                // Wait for the query to execute and retrieve the documents
                var response = new ArrayList<RouteGeojson>();
                    QuerySnapshot querySnapshot = future.get();
        
        // Iterate through each document and calculate total distance
        double totalDistance = 0.0;
        for (QueryDocumentSnapshot document : querySnapshot) {


var routeJson = document.getData().get("route").toString();
List<List<Double>> route = objectMapper.readValue(routeJson, List.class);

            totalDistance += calculateDistance(route);
        }
        
        return totalDistance;
    }
    
  
private double calculateDistance(List<List<Double>> route) {
        double distance = 0.0;
        for (int i = 0; i < route.size() - 1; i++) {
            List<Double> point1 = route.get(i);
            List<Double> point2 = route.get(i + 1);
            double lon1 = Math.toRadians(point1.get(0));
            double lat1 = Math.toRadians(point1.get(1));
            double lon2 = Math.toRadians(point2.get(0));
            double lat2 = Math.toRadians(point2.get(1));
            double dlon = lon2 - lon1;
            double dlat = lat2 - lat1;
            double a = Math.pow(Math.sin(dlat/2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dlon/2), 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
            distance += 6371 * c; // 6371 is the radius of the Earth in kilometers
        }
        return distance;
}


    @GetMapping("/wahoo-geojson")
    public ResponseEntity<List<RouteGeojson>> getAllWahooDataAsGeojson()  {

        try {
            var value = wahooCache.get("wahoo", () -> {
                Firestore db = FirestoreClient.getFirestore();

                // Create a query to retrieve all documents from the "users" collection
                ApiFuture<QuerySnapshot> future = db.collection("wahoo").get();

                // Wait for the query to execute and retrieve the documents
                var response = new ArrayList<RouteGeojson>();
                    QuerySnapshot querySnapshot = future.get();
                    for (DocumentSnapshot document : querySnapshot.getDocuments()) {
                        RouteGeojson routeGeojson = new RouteGeojson();
                        routeGeojson.timeStamp = document.getData().get("timeStamp").toString();

                        var routeJson = document.getData().get("route").toString();
                        List<List<Double>> routeJsonParsed = objectMapper.readValue(routeJson, List.class);
int steps = 30;
if(routeJsonParsed.size()<100){
   steps=1;
}
                        List<List<Double>> extractedRouteJsonParsed = new ArrayList<>();
                        // only add every 30th point to keep JSON low
                        for (int i = 0; i < routeJsonParsed.size(); i += steps) {
                            if (i < routeJsonParsed.size() - 1) {
                                extractedRouteJsonParsed.add(routeJsonParsed.get(i));
                            }
                        }
                        // also add last point
                        extractedRouteJsonParsed.add(routeJsonParsed.get(routeJsonParsed.size() - 1));

                        routeGeojson.route = objectMapper.writeValueAsString(extractedRouteJsonParsed);
                        response.add(routeGeojson);
                    }
                    return response;

            });
            return ResponseEntity.ok(value);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }


    @GetMapping("/location-images")
    public ResponseEntity<List<LocationImage>> getImageLocations() {
        Firestore db = FirestoreClient.getFirestore();
        var response = new ArrayList<LocationImage>();
        ApiFuture<QuerySnapshot> future = db.collection("location_images").get();
        try {
            QuerySnapshot querySnapshot = future.get();
            for (DocumentSnapshot document : querySnapshot.getDocuments()) {
                LocationImage locationImage = new LocationImage();
                locationImage.thumbUrl = document.getString("thumbUrl");
                locationImage.imageUrl = document.getString("imageUrl");
                locationImage.createdDate = (long) document.get("createdDate");
                locationImage.latitude = (double) document.get("latitude");
                locationImage.longitude = (double) document.get("longitude");
                locationImage.comment = String.valueOf(document.get("comment"));
                locationImage.author = document.contains("author") ? String.valueOf(document.get("author")) : null;
                response.add(locationImage);
            }
            return ResponseEntity.ok(response);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/locations")
    public ResponseEntity<List<Location>> getLocations() {
        Firestore db = FirestoreClient.getFirestore();
        var response = new ArrayList<Location>();
        ApiFuture<QuerySnapshot> future = db.collection("locations").get();
        try {
            QuerySnapshot querySnapshot = future.get();
            for (DocumentSnapshot document : querySnapshot.getDocuments()) {
                Location location = new Location();
                location.createdDate = (long) document.get("createdDate");
                location.latitude = (double) document.get("latitude");
                location.longitude = (double) document.get("longitude");
                response.add(location);
            }
            return ResponseEntity.ok(response);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping(path = "/telegram-webhook")
    public @ResponseBody ResponseEntity<TelegramWebhook> receiveTelegramWebhook(@RequestBody TelegramWebhook telegramWebhook) {
        if (telegramWebhook.message == null) {
            return ResponseEntity.notFound().build();
        }
        //store raw webhook
        saveOnFirebase("telegram_raw", telegramWebhook, String.valueOf(telegramWebhook.message.message_id));

        if (telegramWebhook.message.document != null) {
            // data message (image or wahoo)
            return processDocument(telegramWebhook);
        }
        else if (telegramWebhook.message.photo != null) {
            return processPhoto(telegramWebhook);
        }
        else if (telegramWebhook.message.reply_to_message != null && telegramWebhook.message.location == null) {
            // ah its a reply message => comment
            // get the document by message id and store the comment in a new field, then show the comment below the date
            String comment = telegramWebhook.message.text;
            int originalMessageId = telegramWebhook.message.reply_to_message.message_id;
            try {
                Firestore db = FirestoreClient.getFirestore();
                DocumentReference docRef = db.collection("location_images").document(String.valueOf(originalMessageId));
                // Retrieve the document
                ApiFuture<DocumentSnapshot> future = docRef.get();
                DocumentSnapshot document = future.get();

                // Check if the document exists
                if (document.exists()) {
                    // Specify the updates to the document
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("comment", comment);
                    // Update the document
                    ApiFuture<WriteResult> update = docRef.update(updates);
                    System.out.println("Data: " + update.get().toString());
                    return ResponseEntity.ok().build();
                } else {
                    System.out.println("Document not found!");
                    return ResponseEntity.notFound().build();
                }
                //
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }

        }
        else if (telegramWebhook.message.reply_to_message != null && telegramWebhook.message.location != null) {
            // ah its a reply message => comment
            // get the document by message id and store the comment in a new field, then show the comment below the date
            int originalMessageId = telegramWebhook.message.reply_to_message.message_id;
            try {
                Firestore db = FirestoreClient.getFirestore();
                DocumentReference docRef = db.collection("location_images").document(String.valueOf(originalMessageId));
                // Retrieve the document
                ApiFuture<DocumentSnapshot> future = docRef.get();
                DocumentSnapshot document = future.get();

                // Check if the document exists
                if (document.exists()) {
                    // Specify the updates to the document
                    Map<String, Object> updates = new HashMap<>();

                    var longitude = telegramWebhook.message.location.longitude;
                    var latitude = telegramWebhook.message.location.latitude;

                    updates.put("latitude", latitude);
                    updates.put("longitude", longitude);
                    // Update the document
                    ApiFuture<WriteResult> update = docRef.update(updates);
                    System.out.println("Data: " + update.get().toString());
                    return ResponseEntity.ok().build();
                } else {
                    System.out.println("Document not found!");
                    return ResponseEntity.notFound().build();
                }
                //
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }

        }
        else {
            // location message
            Location location = new Location();
            location.longitude = telegramWebhook.message.location.longitude;
            location.latitude = telegramWebhook.message.location.latitude;
            location.createdDate = telegramWebhook.message.date;

            saveOnFirebase("locations", location, String.valueOf(telegramWebhook.message.message_id));
            return ResponseEntity.ok().build();

        }
    }

    private ResponseEntity<TelegramWebhook> processDocument(TelegramWebhook telegramWebhook) {

        String file_id = telegramWebhook.message.document.file_id;
        String file_name = telegramWebhook.message.document.file_name;
        String type = telegramWebhook.message.document.file_name.split("\\.")[1];
        if (type.equals("fit")) {
            WahooRecord wahooRecord = new WahooRecord();
            // file from wahoo /element app

            String fileUrl = uploadFile(file_name, "application/octet-stream", downloadFile(file_id));
            wahooRecord.blobUrl = fileUrl;
            //parse
            okhttp3.ResponseBody responseBody = parseFit(downloadFile(file_id));
            Gson gson = new Gson();

            try {
                FitSequence[] fitSequences = gson.fromJson(responseBody.string(), FitSequence[].class);
                double factor = (180 / Math.pow(2, 31));
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("[");
                for (int i = 0; i < fitSequences.length; i++) {
                    double latitude = fitSequences[i].position_lat * factor;
                    double longitude = fitSequences[i].position_long * factor;
                    if (latitude != 0.0) {
                        stringBuilder.append("[" + longitude + "," + latitude + "]");
                        stringBuilder.append(",");
                    }
                }
                if (fitSequences.length > 0) {
                    stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                }
                stringBuilder.append("]");
                wahooRecord.route = stringBuilder.toString();
                wahooRecord.timeStamp = Arrays.stream(fitSequences).findFirst().get().timestamp;

                saveOnFirebase("wahoo", wahooRecord, String.valueOf(telegramWebhook.message.message_id));
                saveAllOnFirebase(new ArrayList<>(Arrays.asList(fitSequences)), String.valueOf(telegramWebhook.message.message_id));


            } catch (IOException e) {
                throw new RuntimeException(e);
            }


        }
        else {
            LocationImage locationImage = new LocationImage();
            //thumb
            String fileName = telegramWebhook.message.document.file_name;
            var mediaType = URLConnection.guessContentTypeFromName(fileName);

            String thumbUrl = uploadFile("Thumb_" + fileName, mediaType, downloadFile(telegramWebhook.message.document.thumb.file_id));

            locationImage.thumbUrl = thumbUrl;
            locationImage.author = telegramWebhook.message.from.username != null ? telegramWebhook.message.from.username : telegramWebhook.message.from.first_name;
            //image
            BufferedInputStream originalImage = downloadFile(telegramWebhook.message.document.file_id);

            try {
                Metadata metadata = ImageMetadataReader.readMetadata(originalImage);
                GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);

                if (gpsDirectory.getGeoLocation() != null) {
                    double latitude = gpsDirectory.getGeoLocation().getLatitude();
                    double longitude = gpsDirectory.getGeoLocation().getLongitude();
                    long timestampSec;
                    if (gpsDirectory.getGpsDate() != null) {
                        timestampSec = gpsDirectory.getGpsDate().getTime() / 1000;
                    } else {
                        timestampSec = metadata.getFirstDirectoryOfType(ExifDirectoryBase.class).getDate(306).getTime() / 1000;
                    }
                    locationImage.latitude = latitude;
                    locationImage.longitude = longitude;
                    locationImage.createdDate = timestampSec;
                } else {
                    locationImage.latitude = 0;
                    locationImage.longitude = 0;
                    locationImage.createdDate = telegramWebhook.message.date;
                }
            } catch (ImageProcessingException | IOException e) {
                throw new RuntimeException(e);
            }

            String imageUrl = uploadFile(file_name, mediaType, downloadFile(telegramWebhook.message.document.file_id));
            locationImage.imageUrl = imageUrl;

            saveOnFirebase("location_images", locationImage, String.valueOf(telegramWebhook.message.message_id));
        }

        return ResponseEntity.ok(telegramWebhook);
    }
    private ResponseEntity<TelegramWebhook> processPhoto(TelegramWebhook telegramWebhook) {

        LocationImage locationImage = new LocationImage();
        var thumb = telegramWebhook.message.photo.get(2);
        //thumb
        String fileName = thumb.file_unique_id;
        var mediaType = "image/jpeg";

        String thumbUrl = uploadFile("Thumb_" + fileName, mediaType, downloadFile(thumb.file_id));

        locationImage.thumbUrl = thumbUrl;
        locationImage.author = telegramWebhook.message.from.username != null ? telegramWebhook.message.from.username : telegramWebhook.message.from.first_name;
        locationImage.createdDate = telegramWebhook.message.date;

        var photo = telegramWebhook.message.photo.get(telegramWebhook.message.photo.size() - 1);

        String imageUrl = uploadFile(fileName, mediaType, downloadFile(photo.file_id));
        locationImage.imageUrl = imageUrl;

        saveOnFirebase("location_images", locationImage, String.valueOf(telegramWebhook.message.message_id));

        return ResponseEntity.ok(telegramWebhook);
    }


    private void saveAllOnFirebase(ArrayList<FitSequence> fitSequences, String documentId) {
        int batchSize = 500;
        int index = 0;

        while (index < fitSequences.size()) {
            List<FitSequence> batch = fitSequences.subList(index, Math.min(index + batchSize, fitSequences.size()));
            // Process batch here

            WahooRawRecord wahooRawRecord = new WahooRawRecord();
            wahooRawRecord.fitSequences = batch;
            var documentName = String.format("%s_%d_%d", documentId, index, index + batchSize);
            saveOnFirebase("wahoo_raw", wahooRawRecord, documentName);

            index += batchSize;
        }
    }


    private void saveOnFirebase(String collectionName, Object data, String documentId) {
        try {
            Firestore db = FirestoreClient.getFirestore();

            WriteResult future = db.collection(collectionName).document(documentId).set(data).get();
            // Wait for the document to be written to the database
            System.out.println("Added document with ID: " + documentId);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private okhttp3.ResponseBody parseFit(BufferedInputStream bufferedInputStream) {
        OkHttpClient client = new OkHttpClient().newBuilder()
                .build();

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

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private String uploadFile(String id, String mediaType, BufferedInputStream bufferedInputStream) {
        BlobClient blobClient = blobContainerClient.getBlobClient(id);
        var uploadOptions = new BlobParallelUploadOptions(bufferedInputStream);
        uploadOptions.setHeaders(
            new BlobHttpHeaders()
                .setCacheControl("public, max-age=21536000")
                .setContentType(mediaType)
        );
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

