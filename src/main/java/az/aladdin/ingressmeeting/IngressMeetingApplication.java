package az.aladdin.ingressmeeting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IngressMeetingApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngressMeetingApplication.class, args);
    }

}
