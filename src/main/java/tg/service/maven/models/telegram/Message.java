package tg.service.maven.models.telegram;

public class Message {
    public int message_id;
    public From from;
    public Chat chat;
    public int date;

    public Message reply_to_message;

    public Document document;
    public Location location;

    public String text;
}
