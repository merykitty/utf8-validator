package benchmark;

import functionality.FilesTest;
import utf8validator.Utf8Validator;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
public class ValidatorBenchmark {
    @Param({"twitter.json", "utf8-demo.txt", "20k.txt"})
    String testFile;

    byte[] buf;

    @Setup(Level.Trial)
    public void setup() throws IOException, URISyntaxException {
        buf = Files.readAllBytes(Path.of(FilesTest.class.getResource(testFile).toURI()));
    }

//    @Benchmark
    public String jdk() {
        return new String(buf, StandardCharsets.UTF_8);
    }


    @Benchmark
    public boolean vector() {
        return Utf8Validator.validateUtf8(buf);
    }
}
