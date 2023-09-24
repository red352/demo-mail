package com.example.demomail.service;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.IdleManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author LuoYunXiao
 * @since 2023/9/19 19:29
 */
@Service
@Slf4j
@Getter
public class MailService {

    @PreDestroy
    void destroy() {
        if (idleManager != null) idleManager.stop();
        try {
            if (store != null) store.close();
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
        es.close();
        log.info("MailService destroy");
    }

    private final JavaMailSenderImpl mailSender;
    private final MailProperties mailProperties;

    private final ExecutorService es = Executors.newCachedThreadPool();

    private IdleManager idleManager;
    private IMAPStore store;
    private Session session;

    public MailService(final JavaMailSenderImpl mailSender, final MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    @PostConstruct
    /*
    初始化连接参数的操作
     */
    void init() {
        Logger logger = LoggerFactory.getLogger(this.getClass());
        this.session = mailSender.getSession();
        // 开启java mail debug
        if (logger.isTraceEnabled()) {
            this.session.setDebug(true);
        }
        try {
            this.store = (IMAPStore) session.getStore("imap");
            log.info("开始连接中.......");
            store.connect(mailProperties.getUsername(), mailProperties.getPassword());
            if (store.isConnected()) log.info("连接成功");
            this.idleManager = new IdleManager(mailSender.getSession(), es);
        } catch (MessagingException | IOException e) {
            log.error("连接失败");
            destroy();
            throw new RuntimeException(e);
        }
    }

    public void receiveMonitor(String folderName) {
        try {
            IMAPFolder folder = (IMAPFolder) store.getFolder(folderName);
            walkTree(folder).thenPrint();
            folder.open(Folder.READ_WRITE);
            folder.addMessageCountListener(new MessageCountAdapter() {
                @Override
                public void messagesAdded(MessageCountEvent e) {
                    try {
                        for (Message message : e.getMessages()) {
                            message.setFlag(Flags.Flag.SEEN, true);
                            InternetAddress address = (InternetAddress) message.getFrom()[0];
                            String subject = message.getSubject();
                            log.debug("收到邮件了,{},{}", address.getAddress(), subject);
                        }
                        idleManager.watch(folder);
                    } catch (MessagingException ex) {
                        log.error("邮件获取失败，destory service.......", ex);
                        try {
                            folder.close();
                        } catch (MessagingException exc) {
                            throw new RuntimeException(exc);
                        }
                        destroy();
                    }
                }
            });
            idleManager.watch(folder);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
        log.info("执行邮件IDLE监听线程");
    }

    public void readMail() {
        try {
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);
            Message message = folder.getMessage(folder.getMessageCount() - 1);
            log.info("主题是:{}，时间:{}", message.getSubject(), message.getReceivedDate());
            log.info("内容类型是:{}", message.getContentType());
            if (message.getContent() instanceof Multipart) {
                Multipart content = (MimeMultipart) message.getContent();
                for (int i = 0; i < content.getCount(); i++) {
                    BodyPart bodyPart = content.getBodyPart(i);
                    ContentType contentType = new ContentType(bodyPart.getContentType());
                    if (contentType.getPrimaryType().equalsIgnoreCase("text")) {
                        try (var inputStream = new BufferedInputStream(bodyPart.getInputStream())) {
                            var buffer = new byte[8192]; // 缓冲区大小
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                String string = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                                System.out.println(string);
                            }
                        }
                    }
                }
            }
            folder.close();
            destroy();
        } catch (MessagingException | IOException e) {
            throw new RuntimeException(e);
        }

    }

    private Node walkTree(Folder folder, int level, Node nodeParent) throws MessagingException {
        if (!folder.exists()) {
            return nodeParent;
        }
        String name = folder.getName();
        if (Objects.isNull(name) || name.equals("null") || name.isEmpty()) {
            name = "邮件根目录";
        }

        Node nodeSon = new Node();
        nodeSon.setName(name);
        nodeSon.setLevel(level);
        if (nodeParent == null) {
            nodeParent = nodeSon;
        } else {
            nodeParent.getChildren().add(nodeSon);
        }
        for (Folder folderSon : folder.list()) {
            walkTree(folderSon, level + 1, nodeParent);
        }
        return nodeParent;
    }

    private Node walkTree(Folder folder) throws MessagingException {
        return walkTree(folder, 0, null);
    }


    @Getter
    @Setter
    @Slf4j
    public static class Node {
        private String name;
        private int level;
        private List<Node> children = new ArrayList<>();

        private void thenPrint() {
            log.debug("-".repeat(this.getLevel()) + this.getName() + " " + this.getLevel());
            for (Node nodeSon : this.getChildren()) {
                nodeSon.thenPrint();
            }
        }
    }

    private static void readMessages(Folder folder, int num) throws MessagingException {
        Arrays.stream(folder.getMessages(folder.getMessageCount() - num + 1, folder.getMessageCount())).forEach(message -> {
            try {
                String address = ((InternetAddress) message.getFrom()[0]).getAddress();
                String subject = message.getSubject();
                String receivedDate = LocalDateTime.ofInstant(message.getReceivedDate().toInstant(), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                log.debug("address: {}, subject: {}, receivedDate: {}", address, subject, receivedDate);

            } catch (MessagingException e) {
                throw new RuntimeException(e);
            }
        });
    }


}
