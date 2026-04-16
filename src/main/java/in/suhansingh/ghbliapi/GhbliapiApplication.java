package in.suhansingh.ghbliapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class GhbliapiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GhbliapiApplication.class, args);
	}

}
