package tg.service.maven.models;

import javax.annotation.Nullable;

public class LocationImage {
    public String thumbUrl;

    public long createdDate;
    public String imageUrl;
    public double latitude;
    public double longitude;
    public String comment;

    @Nullable
    public String author;

}
