package xizhang.rc_xizhang_coding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RcXiZhangCodingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RcXiZhangCodingApplication.class, args);
    }

}
