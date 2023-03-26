package tg.service.maven.models.telegram;

public class TelegramWebhook{
    public int update_id;
    public Message message;
}

class Chat{
    public long id;
    public String first_name;
    public String type;
}


