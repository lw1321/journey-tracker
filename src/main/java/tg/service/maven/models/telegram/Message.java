package tg.service.maven.models.telegram;

import java.util.List;

public class Message {
    public int message_id;
    public From from;
    public Chat chat;
    public int date;

    public Message reply_to_message;

    public Document document;

    public List<PhotoSize> photo;
    public Location location;

    public String text;
}
