package com.example.demomail;

import com.example.demomail.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * @author LuoYunXiao
 * @since 2023/9/20 19:26
 */
@Component
@RequiredArgsConstructor
public class MailRunner implements CommandLineRunner {

    private final MailService mailService;

    @Override
    public void run(String... args) {
//        mailService.receiveMonitor("INBOX");
        mailService.readMail();
    }
}
