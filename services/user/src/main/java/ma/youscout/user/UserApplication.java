package ma.youscout.user;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** Démarre le service User et découvre ses composants Spring dans ce package. */
@SpringBootApplication
public class UserApplication {
    public static void main(String[] args) { SpringApplication.run(UserApplication.class, args); }
}
