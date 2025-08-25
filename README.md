# Reactive Stream to CSV Exporter

This project is a **Java 11+ reactive application** that simulates a live data stream (Bid, Ask, Volume as JSON over TCP) and exports the stream to a **CSV file** in real time. It uses **Project Reactor** and **Reactor Netty** to ensure an event-driven, non-blocking architecture with proper backpressure handling.

---

## Features

* Simulates a streaming data source via **TCP server**.
* TCP client consumes and processes the stream in real-time.
* Parses JSON into objects using **Jackson**.
* Exports to CSV with configurable:

    * Output file path
    * Flush interval
    * Port
    * Buffer size
    * Max queue size
* Proper backpressure handling (`onBackpressureBuffer`).
* Event-driven, fully non-blocking pipeline.

---

## Requirements

* Java **11 or higher** (tested with JDK 17)
* Maven **3.6+**

---

## Build Instructions

Clone the repository and build the shaded JAR:

```bash
mvn clean package
```

After building, the runnable JAR will be located at:

```
target/Demo-1.0-SNAPSHOT-shaded.jar
```

### Run Instructions

Run with default settings:

```bash
java -jar target/Demo-1.0-SNAPSHOT-shaded.jar
```

**Default behavior:**

* Starts TCP simulator server on port 9000
* Connects client to the server
* Exports data into `./ticks.csv`
* Writes batches every 5 seconds or every 1000 records, whichever comes first

You can override defaults using command-line arguments:

| Argument         | Default     | Description                       |
| ---------------- | ----------- | --------------------------------- |
| --out=PATH       | ./ticks.csv | Output CSV file path              |
| --interval-sec=N | 5           | Flush interval in seconds         |
| --port=N         | 9000        | TCP port for simulator and client |
| --buffer=N       | 1000        | Batch size before flush           |
| --max-queue=N    | 10000       | Max queue size before dropping    |

**Example usage:**

```bash
java -jar target/Demo-1.0-SNAPSHOT-shaded.jar --out=./data/market_ticks.csv --interval-sec=10 --port=8080 --buffer=500 --max-queue=20000
```

### Example CSV Output

```
Timestamp,Bid,Ask,Volume
1692974035123,100.34,100.87,50
1692974035173,99.85,100.44,73
1692974035223,100.12,100.65,12
```

### Stopping the Application

Press **CTRL + C**. The shutdown hook ensures the TCP server is closed cleanly.

**Notes:**

* The built-in simulator is for testing; in production, you would connect to an actual TCP/WebSocket feed.
* Logging can be extended using SLF4J + Logback.
* Backpressure handling ensures that no data is lost if the consumer lags behind.

### Maven `pom.xml` Notes

Ensure the `<mainClass>` in your `maven-shade-plugin` points to `software.kasunkavinda.App`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.5.3</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>software.kasunkavinda.App</mainClass>
                    </transformer>
                </transformers>
            </configuration>
        </execution>
    </executions>
</plugin>
```

After packaging, you can run the JAR directly with:

```bash
java -jar target/Demo-1.0-SNAPSHOT-shaded.jar
```
