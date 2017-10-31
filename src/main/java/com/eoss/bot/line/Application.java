/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.eoss.bot.line;

import com.eoss.brain.*;
import com.eoss.brain.command.line.BizWakeupCommandNode;
import com.eoss.brain.command.line.WakeupCommandNode;
import com.eoss.brain.net.Context;
import com.eoss.brain.net.GAEStorageContext;
import com.eoss.brain.net.GAEWebIndexSupportContext;
import com.eoss.util.GAEWebStream;
import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.client.MessageContentResponse;
import com.linecorp.bot.model.PushMessage;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.action.Action;
import com.linecorp.bot.model.action.MessageAction;
import com.linecorp.bot.model.action.URIAction;
import com.linecorp.bot.model.event.*;
import com.linecorp.bot.model.event.Event;
import com.linecorp.bot.model.event.message.*;
import com.linecorp.bot.model.event.source.GroupSource;
import com.linecorp.bot.model.event.source.RoomSource;
import com.linecorp.bot.model.event.source.Source;
import com.linecorp.bot.model.message.*;
import com.linecorp.bot.model.message.imagemap.ImagemapArea;
import com.linecorp.bot.model.message.imagemap.ImagemapBaseSize;
import com.linecorp.bot.model.message.imagemap.URIImagemapAction;
import com.linecorp.bot.model.message.template.ButtonsTemplate;
import com.linecorp.bot.model.message.template.ConfirmTemplate;
import com.linecorp.bot.model.profile.UserProfileResponse;
import com.linecorp.bot.spring.boot.annotation.EventMapping;
import com.linecorp.bot.spring.boot.annotation.LineMessageHandler;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;

@SpringBootApplication
@LineMessageHandler
@EnableConfigurationProperties(EOSSBotProperties.class)
public class Application {

    @Autowired
    private EOSSBotProperties eossBotProperties;

    @Autowired
    private LineMessagingClient lineMessagingClient;

    private Context context;

    private final DateFormat df = new SimpleDateFormat("yyyy/MMM/dd HH:ss:mm", new Locale("th"));

    Map<String, Session> sessionContextMap = new HashMap<>();

    Map<String, UserProfileResponse> profileMap = new HashMap<>();

    List<String> adminIdList;

    public static void main(String[] args) {

        Locale.setDefault(new Locale("th", "TH"));
        SpringApplication.run(Application.class, args);
    }

    private List<String> getAdminIdList() {

        if (adminIdList==null) {
            adminIdList = new ArrayList<>();

            adminIdList.add("Uee73cf96d1dbe69a260d46fc03393cfd");

            GAEWebStream adminList = new GAEWebStream(eossBotProperties.getName() + ".admin.txt");;
            String userIds = adminList.read().trim();
            if (!userIds.isEmpty()) {

                String[] lines = userIds.split(System.lineSeparator());

                String userId;
                for (String line : lines) {
                    userId = line.trim();
                    if (userId.isEmpty()) continue;
                    if (!adminIdList.contains(userId)) {
                        adminIdList.add(userId);
                    }
                }
            }
        }
        return adminIdList;
    }

    private Context getContext() {
        if (context==null) {
            context = new GAEWebIndexSupportContext(new GAEStorageContext(eossBotProperties.getName()))
                    .admin(getAdminIdList())
                    .callback((nodeEvent)->{
                        callback(nodeEvent);
                    })
                    .domain(eossBotProperties.getDomain());
        }
        return context;
    }

    private Session getContext(final MessageEvent<? extends MessageContent> event) {

        String sessionId = event.getSource().getSenderId();

        Session session = sessionContextMap.get(sessionId);
        if (session ==null) {

            push(sessionId, toLineMessage(MessageTemplate.STICKER + "1:405"));
            session = new Session(getContext());
            new BizWakeupCommandNode(session).execute(null);
            sessionContextMap.put(sessionId, session);
        }

        String userId = event.getSource().getUserId();

        UserProfileResponse userProfile = profileMap.get(userId);
        if (userProfile==null) {
            lineMessagingClient
                    .getProfile(userId)
                    .whenComplete((profile, throwable) -> {

                        if (throwable != null) return;
                        profileMap.put(userId, profile);
                    });
        }

        return session;
    }

    private MessageObject toEOSSMessage(MessageEvent<? extends MessageContent> event) throws InterruptedException, ExecutionException {

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", event.getSource().getUserId());
        attributes.put("senderId", event.getSource().getSenderId());
        attributes.put("event", event);

        String text = "";

        if (event.getMessage() instanceof TextMessageContent) {
            text = ((TextMessageContent)event.getMessage()).getText();
        }

        if (event.getMessage() instanceof StickerMessageContent) {
            String packageId = ((StickerMessageContent)event.getMessage()).getPackageId();
            String stickerId = ((StickerMessageContent)event.getMessage()).getStickerId();
            text = MessageTemplate.STICKER + packageId + ":" + stickerId;
        }

        if (event.getMessage() instanceof ImageMessageContent) {
            MessageContentResponse response = lineMessagingClient.getMessageContent(event.getMessage().getId()).get();
            String url = saveContent("jpg", response);
            text = MessageTemplate.IMAGE + url;
        }

        if (event.getMessage() instanceof AudioMessageContent) {
            MessageContentResponse response = lineMessagingClient.getMessageContent(event.getMessage().getId()).get();
            String url = saveContent("mp4", response);
            text = MessageTemplate.AUDIO + url;
        }

        if (event.getMessage() instanceof VideoMessageContent) {
            MessageContentResponse response = lineMessagingClient.getMessageContent(event.getMessage().getId()).get();
            String url = saveContent("mp4", response);
            text = MessageTemplate.VIDEO + url;
        }

        attributes.put("text", text);

        return MessageObject.build(attributes);
    }

    public Message toLineMessage(final String text) {

        if (text.startsWith(MessageTemplate.STICKER)) {

            String stickerString = text.replace(MessageTemplate.STICKER, "").replace("?", "");
            String [] ids = stickerString.split(":", 2);
            try {
                int packageId = Integer.parseInt(ids[0]);
                int stickerId = Integer.parseInt(ids[1]);
                return new StickerMessage(Integer.toString(packageId), Integer.toString(stickerId));
            } catch (Exception e) {
                e.printStackTrace();
            }

        } else if (text.startsWith(MessageTemplate.IMAGE)) {

            String url = text.replace(MessageTemplate.IMAGE, "");
            return new ImageMessage(url, url);
        } else if (text.startsWith(MessageTemplate.IMAGEMAP)) {

            String url = text.replace(MessageTemplate.IMAGEMAP, "");
            return new ImagemapMessage(url,
                    "Download Here!",
                    new ImagemapBaseSize(1040, 1040),
                    Arrays.asList(
                            new URIImagemapAction(
                                    url,
                                    new ImagemapArea(
                                            0, 0, 520, 520
                                    )
                            )));

        } else if (text.startsWith(MessageTemplate.AUDIO)) {

            String url = text.replace(MessageTemplate.AUDIO, "");
            return new AudioMessage(url, 60 * 1000);

        } else if (text.startsWith(MessageTemplate.VIDEO)) {

            String url = text.replace(MessageTemplate.VIDEO, "");
            return new VideoMessage(url, GAEWebStream.URL_STORAGE_BIN +"preview.png");

        } else if (text.startsWith(MessageTemplate.MODE)) {

            String name = text.replace(MessageTemplate.MODE, "");
            return new TemplateMessage(
                    "โหมด",
                    new ConfirmTemplate("ต้องการเข้าสู่โหมด " + name + " หรือไม่?",
                            new MessageAction("Yes", MessageTemplate.MODE_HOOK + name),
                            new MessageAction("No", "ไม่")));

        } else if (text.startsWith("https://")) {

            String [] templates = text.split(" ", 2);
            String url = templates[0].trim();
            String webName = url.replace("https://", "").replace("www.", "");
            boolean isImageURL = url.endsWith("jpg") || url.endsWith("jpeg") || url.endsWith("png") || url.endsWith("JPG") || url.endsWith("PNG") || url.endsWith("JPEG");

            String detail;
            List<String> menuList = new ArrayList<>();
            if (templates.length==1) {

                if (isImageURL) {
                    /*
                    if (detail.length()>60)
                        detail = detail.substring(0, 60-3) + "...";

                    return new TemplateMessage(
                            "ภาพ",
                            new ButtonsTemplate(
                                    url,
                                    "ภาพ",
                                    detail,
                                    Arrays.asList(new URIAction("ดูภาพ", url))));
                                    */
                    return new ImageMessage(url, url);
                }

                detail = webName;
                if (detail.length()>240)
                    detail = detail.substring(0, 240-3) + "...";

                return new TemplateMessage(
                        "เวปไซท์",
                        new ConfirmTemplate( detail,
                                new URIAction("เข้าเวป", url),
                                new MessageAction("ขอบคุณ", "ขอบคุณ")));
            }

            detail = templates[1];

            if (isImageURL) {

                String [] menus = templates[1].split("\n", 5);
                detail = menus[0];

                if (detail.length()>60)
                    detail = detail.substring(0, 60-3) + "...";

                if (menus.length>1) {
                    String menuItem;
                    for (int i=1;i<menus.length;i++) {
                        if (menus[i].trim().isEmpty()) continue;
                        menuItem = menus[i].trim();
                        menuList.add(menuItem);
                    }
                }

                String hostName;
                URI uri;
                try {
                    uri = new URI(url);
                    hostName = uri.getHost();
                } catch (URISyntaxException e) {
                    hostName = url;
                }

                List<Action> actionList = new ArrayList<>();

                if (menuList.isEmpty()) {
                    actionList.add(new MessageAction("ถัดไป", "ยังไง"));
                } else {
                    String[] items;
                    String message, label;
                    boolean isURL;
                    for (String menuItem : menuList) {
                        isURL = menuItem.startsWith("http://")||menuItem.startsWith("https://");
                        if (isURL)
                            menuItem = menuItem.replace("https://", "").replace("http://", "");
                        items = menuItem.split(":", 2);
                        if (items.length == 2) {
                            message = items[0].trim();
                            label = items[1].trim();
                        } else {
                            message = items[0].trim();
                            label = message;
                        }
                        if (label.length() > 20)
                            label = label.substring(0, 20 - 3) + "...";
                        if (message.length() > 300)
                            message = message.substring(0, 300);

                        if (isURL) {
                            actionList.add(new URIAction(label, "https://" + message));
                            try {
                                uri = new URI("https://" + message);
                                hostName = uri.getHost();
                            } catch (URISyntaxException e) {
                                hostName = message;
                            }
                        } else {
                            actionList.add(new MessageAction(label, message));
                        }
                    }
                }

                return new TemplateMessage(
                        "โปรดเลือก",
                        new ButtonsTemplate(
                                url,
                                hostName,
                                detail,actionList));
            }

            if (detail.length()>240)
                detail = detail.substring(0, 240-3) + "...";

            return new TemplateMessage(
                    "เวปไซท์",
                    new ConfirmTemplate( detail,
                            new URIAction("เข้าเวป", url),
                            new MessageAction("ขอบคุณ", "ขอบคุณ")));
        }

        return new TextMessage(text);
    }

    @EventMapping
    public void handleTextMessageEvent(MessageEvent<TextMessageContent> event) {
        Session session = getContext(event);
        try {
            reply(event.getReplyToken(), toLineMessage(session.parse(toEOSSMessage(event))));
        } catch (InterruptedException | ExecutionException e) {
            reply(event.getReplyToken(), new TextMessage("..."));
            throw new RuntimeException(e);
        }
    }

    @EventMapping
    public void handleStickerMessageEvent(MessageEvent<StickerMessageContent> event) {
        Session session = getContext(event);
        try {
            reply(event.getReplyToken(), toLineMessage(session.parse(toEOSSMessage(event))));
        } catch (InterruptedException | ExecutionException e) {
            reply(event.getReplyToken(), new TextMessage("..."));
            throw new RuntimeException(e);
        }
    }

    @EventMapping
    public void handleImageMessageEvent(MessageEvent<ImageMessageContent> event) throws IOException {
        Session session = getContext(event);
        try {
            reply(event.getReplyToken(), toLineMessage(session.parse(toEOSSMessage(event))));
        } catch (InterruptedException | ExecutionException e) {
            reply(event.getReplyToken(), new TextMessage("..."));
            throw new RuntimeException(e);
        }
    }

    @EventMapping
    public void handleAudioMessageEvent(MessageEvent<AudioMessageContent> event) throws IOException {
        Session session = getContext(event);
        try {
            reply(event.getReplyToken(), toLineMessage(session.parse(toEOSSMessage(event))));
        } catch (InterruptedException | ExecutionException e) {
            reply(event.getReplyToken(), new TextMessage("..."));
            throw new RuntimeException(e);
        }
    }

    @EventMapping
    public void handleVideoMessageEvent(MessageEvent<VideoMessageContent> event) throws IOException {
        Session session = getContext(event);
        try {
            reply(event.getReplyToken(), toLineMessage(session.parse(toEOSSMessage(event))));
        } catch (InterruptedException | ExecutionException e) {
            reply(event.getReplyToken(), new TextMessage("..."));
            throw new RuntimeException(e);
        }
    }

    @EventMapping
    public void handleUnfollowEvent(UnfollowEvent event) {

        GAEWebStream unFollowLog = new GAEWebStream(eossBotProperties.getName() + ".unfollow.txt");

        StringBuilder text = new StringBuilder(unFollowLog.read());
        text.append(df.format(new Date()) + "\t" + event.getSource().getUserId());
        text.append(System.lineSeparator());
        unFollowLog.write(text.toString());
    }

    @EventMapping
    public void handleFollowEvent(FollowEvent event) {

        GAEWebStream followLog=  new GAEWebStream(eossBotProperties.getName() + ".follow.txt");;

        final StringBuilder text = new StringBuilder(followLog.read());

        lineMessagingClient
                .getProfile(event.getSource().getUserId())
                .whenComplete((profile, throwable) -> {

                    text.append(System.lineSeparator());
                    try {
                        if (throwable != null) {
                            text.append(df.format(new Date()) + "\t" + event.getSource().getUserId());
                            text.append(System.lineSeparator());
                            return;
                        }
                        text.append(df.format(new Date()) + "\t" + profile.getUserId() + "\t" + profile.getDisplayName() + "\t" + profile.getPictureUrl());
                        text.append(System.lineSeparator());

                    } finally {
                        followLog.write(text.toString());
                    }

                });

    }

    @EventMapping
    public void handleJoinEvent(JoinEvent event) {

        GAEWebStream joinLog = new GAEWebStream(eossBotProperties.getName() + ".join.txt");;

        StringBuilder text = new StringBuilder(joinLog.read());

        if (event.getSource() instanceof  RoomSource)
            text.append(df.format(new Date()) + "\t" + ((RoomSource)event.getSource()).getRoomId());
        else if (event.getSource() instanceof GroupSource)
            text.append(df.format(new Date()) + "\t" + ((GroupSource)event.getSource()).getGroupId());
        text.append(System.lineSeparator());

        joinLog.write(text.toString());
    }

    @EventMapping
    public void handleLeaveEvent(JoinEvent event) {

        GAEWebStream leaveLog = new GAEWebStream(eossBotProperties.getName() + ".leave.txt");

        StringBuilder text = new StringBuilder(leaveLog.read());

        if (event.getSource() instanceof  RoomSource)
            text.append(df.format(new Date()) + "\t" + ((RoomSource)event.getSource()).getRoomId());
        else if (event.getSource() instanceof GroupSource)
            text.append(df.format(new Date()) + "\t" + ((GroupSource)event.getSource()).getGroupId());
        text.append(System.lineSeparator());

        leaveLog.write(text.toString());
    }

    private static String saveContent(String ext, MessageContentResponse responseBody) {

        String fileName = LocalDateTime.now().toString() + '-' + UUID.randomUUID().toString() + '.' + ext;

        GAEWebStream webStream = new GAEWebStream(fileName);

        try {

            webStream.write(responseBody.getStream());
            return webStream.getURL();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private void reply(@NonNull String replyToken, @NonNull Message message) {
        reply(replyToken, Collections.singletonList(message));
    }

    private void reply(@NonNull String replyToken, @NonNull List<Message> messages) {
        try {
            lineMessagingClient
                    .replyMessage(new ReplyMessage(replyToken, messages))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void push(@NonNull String senderId, @NonNull Message message) {
        push(senderId, Collections.singletonList(message));
    }

    private void push(@NonNull String senderId, @NonNull List<Message> messages) {
        try {
            lineMessagingClient
                    .pushMessage(new PushMessage(senderId, messages))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @EventMapping
    public void handleDefaultMessageEvent(Event event) {
    }

    String getDisplayName(String userId, String suffix) {
        String displayName = "";
        UserProfileResponse userProfile = profileMap.get(userId);
        if (userProfile!=null) {
            displayName = userProfile.getDisplayName() + suffix;
        }
        return displayName;
    }

    String getUID(String displayName) {
        for (Map.Entry<String, UserProfileResponse> entry:profileMap.entrySet()) {
            if (entry.getValue().getDisplayName().equals(displayName)) return entry.getKey();
        }
        return null;
    }

    void callback(NodeEvent nodeEvent) {

        if (nodeEvent.event==NodeEvent.Event.Leave) {

            String displayName = getDisplayName(nodeEvent.messageObject.attributes.get("userId").toString(), "! ");
            String lineId = eossBotProperties.getLineId();
            if (lineId==null)
                lineId = "%40nhj5856v";
            else
                lineId = lineId.replace("@", "%40");

            push(nodeEvent.messageObject.attributes.get("senderId").toString(), toLineMessage( displayName + nodeEvent.messageObject + "https://line.me/R/ti/p/" + lineId));
            leave((Event)nodeEvent.messageObject.attributes.get("event"));
            return;
        }

        if (nodeEvent.event==NodeEvent.Event.LateReply) {

            String [] responses = nodeEvent.messageObject.toString().split("\n");
            for (String response:responses) {
                push(nodeEvent.messageObject.attributes.get("senderId").toString(), toLineMessage(response));
            }

            return;
        }

        if (nodeEvent.event==NodeEvent.Event.ReservedWords) {

            String displayName = getDisplayName(nodeEvent.messageObject.attributes.get("userId").toString(), "?");
            push(nodeEvent.messageObject.attributes.get("senderId").toString(), toLineMessage(nodeEvent.messageObject + "เหรอฮะ" + " " + displayName));
            return;
        }

        if (nodeEvent.event==NodeEvent.Event.RegisterAdmin) {

            String senderId = nodeEvent.messageObject.attributes.get("senderId").toString();
            String displayName = nodeEvent.messageObject.toString();
            String userId = getUID(displayName);
            if (userId!=null) {

                adminIdList.add(userId);

                push(senderId, toLineMessage( "ยินดีด้วย " + displayName + " ได้ถูกเพิ่มเป็นผู้ดูแลแล้ว!"));

                GAEWebStream adminList = new GAEWebStream(eossBotProperties.getName() + ".admin.txt");;

                StringBuilder text = new StringBuilder(adminList.read());
                if (text.indexOf(userId)==-1) {
                    text.append(userId);
                    text.append(System.lineSeparator());
                    adminList.write(text.toString());
                }

            } else {
                push(senderId, toLineMessage( "ไม่พบรายชื่อ " + displayName + " ในนี้"));
            }
            return;
        }

        if (nodeEvent.event == NodeEvent.Event.Recursive) {
            push(nodeEvent.messageObject.attributes.get("senderId").toString(), toLineMessage(nodeEvent.messageObject.toString()));
            return;
        }
    }

    void leave(Event event) {
        try {
            Source source = event.getSource();
            if (source instanceof GroupSource) {
                lineMessagingClient.leaveGroup(((GroupSource) source).getGroupId()).get();
            } else if (source instanceof RoomSource) {
                lineMessagingClient.leaveRoom(((RoomSource) source).getRoomId()).get();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
