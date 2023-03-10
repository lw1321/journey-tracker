package tg.service.maven;

public class TelegramWebhook{
    public int update_id;
    public Message message;
}

class Message{
    public int message_id;
    public From from;
    public Chat chat;
    public int date;

    public Message reply_to_message;

    public Document document;
    public Location location;

    public String text;
}
class Chat{
    public long id;
    public String first_name;
    public String type;
}

class Document{
    public String file_name;
    public String mime_type;
    public Thumb thumb;
    public String file_id;
    public String file_unique_id;
    public int file_size;
    public String blob_url;
}

class From{
    public long id;
    public boolean is_bot;
    public String first_name;
    public String language_code;
}


class Location{
    public double latitude;
    public double longitude;
    public long createdDate;
    public int messageId;
}
class Thumb{
    public String file_id;
    public String file_unique_id;
    public int file_size;
    public int width;
    public int height;
    public String blob_url;
}

