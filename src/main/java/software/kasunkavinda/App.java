package software.kasunkavinda;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class App {

    static class Config {
        final String outPath;
        final int intervalSec;
        final int port;
        final int bufferBatch;
        final int maxQueue;

        Config(String outPath, int intervalSec, int port, int bufferBatch, int maxQueue) {
            this.outPath = outPath;
            this.intervalSec = intervalSec;
            this.port = port;
            this.bufferBatch = bufferBatch;
            this.maxQueue = maxQueue;
        }

        static Config fromArgs(String[] args) {
            String out = "./ticks.csv";
            int interval = 5;
            int port = 9000;
            int buffer = 1000;
            int maxQueue = 10000;
            for (String a : args) {
                if (a.startsWith("--out=")) out = a.substring(6);
                else if (a.startsWith("--interval-sec=")) interval = Integer.parseInt(a.substring(15));
                else if (a.startsWith("--port=")) port = Integer.parseInt(a.substring(7));
                else if (a.startsWith("--buffer=")) buffer = Integer.parseInt(a.substring(9));
                else if (a.startsWith("--max-queue=")) maxQueue = Integer.parseInt(a.substring(12));
            }
            return new Config(out, interval, port, buffer, maxQueue);
        }
    }

    static class Tick {
        public long timestamp;
        public double bid;
        public double ask;
        public long volume;

        public String toCsvLine() {
            return timestamp + "," + bid + "," + ask + "," + volume + "\n";
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static void main(String[] args) {
        Config cfg = Config.fromArgs(args);
        System.out.println("Starting with config: " + cfg.outPath);

        // Start simulator server that emits JSON ticks
        Connection server = startSimulatorServer(cfg.port);

        File out = new File(cfg.outPath);
        AtomicBoolean headerWritten = new AtomicBoolean(false);

        // Connect client and process data
        TcpClient.create()
                .host("127.0.0.1")
                .port(cfg.port)
                .connect()
                .flatMapMany(conn -> conn.inbound()
                        .receive()
                        .asString(StandardCharsets.UTF_8)
                        .flatMap(s -> Flux.fromArray(s.split("\n"))))
                .flatMap(json -> parse((String) json).onErrorResume(e -> Mono.empty()))
                .onBackpressureBuffer(cfg.maxQueue,
                        drop -> System.err.println("Dropping tick due to overflow"),
                        reactor.core.publisher.BufferOverflowStrategy.DROP_OLDEST)
                .bufferTimeout(cfg.bufferBatch, Duration.ofSeconds(cfg.intervalSec))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(batch -> writeBatch(out, headerWritten, batch))
                .doOnError(e -> System.err.println("Stream error: " + e))
                .blockLast();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.disposeNow();
            } catch (Throwable ignored) {}
        }));
    }

    private static Mono<Tick> parse(String json) {
        return Mono.fromCallable(() -> MAPPER.readValue(json, Tick.class));
    }

    private static Mono<Void> writeBatch(File out, AtomicBoolean headerWritten, List<Tick> batch) {
        if (batch.isEmpty()) return Mono.empty();
        return Mono.fromRunnable(() -> {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(out, true))) {
                if (!headerWritten.getAndSet(true)) {
                    w.write("Timestamp,Bid,Ask,Volume\n");
                }
                for (Tick t : batch) {
                    w.write(t.toCsvLine());
                }
                w.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // Simulator server generating fake Bid/Ask/Volume ticks
    private static Connection startSimulatorServer(int port) {
        Random rnd = new Random();
        return (Connection) TcpServer.create()
                .host("127.0.0.1")
                .port(port)
                .handle((in, out) -> {
                    Flux<String> ticks = Flux.interval(Duration.ofMillis(50))
                            .map(i -> {
                                long ts = Instant.now().toEpochMilli();
                                double bid = 100 + rnd.nextGaussian();
                                double ask = bid + 0.5 + Math.abs(rnd.nextGaussian() * 0.05);
                                long vol = 1 + rnd.nextInt(100);
                                return String.format("{\"timestamp\":%d,\"bid\":%.2f,\"ask\":%.2f,\"volume\":%d}\n",
                                        ts, bid, ask, vol);
                            });
                    return out.sendString(ticks);
                })
                .bindNow();
    }
}
