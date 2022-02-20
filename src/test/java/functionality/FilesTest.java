package functionality;

import org.junit.jupiter.api.Test;
import utf8validator.Utf8Validator;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FilesTest {
    @Test
    public void test() throws IOException, URISyntaxException {
        String[] resources = {"twitter.json", "utf8-demo.txt", "utf8-demo-invalid.txt", "20k.txt"};
        for (String resource : resources) {
            var buf = Files.readAllBytes(Path.of(this.getClass().getResource(resource).toURI()));
            boolean valid = Utf8Validator.validate(buf);
            assertNotEquals(resource.contains("invalid"), valid, "Incorrect result, file:" + resource);
        }
    }
}
