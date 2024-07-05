package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;

import javafx.application.Platform;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.Scene;
import javafx.stage.Stage;


/**
 * Kuorui Chiang
 * CITI - FORAGE
 */
public class App extends Application{
    private static LineChart<Number, Number> lineChart;
    private static XYChart.Series<Number, Number> series;
    @Override
    public void start(Stage stage) {
        stage.setTitle("Dow Jones Industrial Average");
        final NumberAxis xAxis = new NumberAxis();
        final NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Time (seconds)");
        yAxis.setLabel("Stock Price (USD)");
        lineChart = new LineChart<>(xAxis, yAxis);
        lineChart.setTitle("Dow Jones Industrial Average Stock Price");
        series = new XYChart.Series<>();
        lineChart.getData().add(series);
        Scene scene = new Scene(lineChart, 800, 600);
        stage.setScene(scene);
        stage.show();
        new Thread(this::updateData).start(); // start a background thread for querying and updating the chart data
    }

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final String YAHOO_FINANCE_URL = "https://query2.finance.yahoo.com/v7/finance/quote?symbols=DJI&crumb=";
    private static final String COOKIE_URL = "https://fc.yahoo.com";
    private static final String CRUMB_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 2000;

    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final BlockingQueue<StockData> stockDataQueue = new LinkedBlockingQueue<>();

    public void updateData() {
        // This is the loop for querying data
        while (true) {
            try {
                long startSeconds = Instant.now().getEpochSecond();
                String cookie = getCachedCookie();
                String crumb = getCachedCrumb();

                if (cookie == null || crumb == null) {
                    cookie = fetchWithRetry(() -> fetchCookie());
                    TimeUnit.SECONDS.sleep(2);
                    String finalCookie = cookie;
                    crumb = fetchWithRetry(() -> fetchCrumb(finalCookie));
                    cache.put("cookie", cookie);
                    cache.put("crumb", crumb);
                }
                fetchStockData(cookie, crumb);
                // Wait before repeating the query
                int waitTimeMs = 5000; // wait time in milliseconds between queries
                Thread.sleep(waitTimeMs);
            } catch (IOException | InterruptedException e) {
                log.error("Error in main process", e);
            }
        }
    }

    private static String getCachedCookie() {
        return cache.get("cookie");
    }
    private static String getCachedCrumb() {
        return cache.get("crumb");
    }
    private static String fetchCookie() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(COOKIE_URL))
                .build();

        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        Optional<String> setCookie = response.headers().firstValue("set-cookie");

        if (setCookie.isPresent()) {
            log.info("Fetched cookie: {}", setCookie.get());
            return setCookie.get();
        } else {
            throw new IOException("Failed to fetch set-cookie from Yahoo Finance");
        }
    }

    private static String fetchCrumb(String cookie) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CRUMB_URL))
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            log.info("Crumb response: {}", response.body());
            return response.body();
        } else if (response.statusCode() == 429) {
            log.warn("Rate limit exceeded (HTTP 429). Waiting before retry...");
            throw new IOException("Rate limit exceeded (HTTP 429)");
        } else {
            log.error("Failed to fetch crumb. Response code: {}, Response body: {}", response.statusCode(), response.body());
            throw new IOException("Failed to fetch crumb from Yahoo Finance");
        }
    }

    private static void fetchStockData(String cookie, String crumb) throws IOException, InterruptedException {
        String url = YAHOO_FINANCE_URL + crumb;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build();

        String symbol = "^DJI"; // stock symbol for the Dow Jones Industrial Average
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
//            log.info("Stock Data: {}", response.body());
            parseAndStoreStockData(response.body());
        } else if (response.statusCode() == 429) {
            log.warn("Rate limit exceeded (HTTP 429). Waiting before retry...");
            throw new IOException("Rate limit exceeded (HTTP 429)");
        } else {
            log.error("Failed to fetch stock data. Response code: {}, Response body: {}", response.statusCode(), response.body());
            throw new IOException("Failed to fetch stock data from Yahoo Finance");
        }
    }

    private static void parseAndStoreStockData(String responseBody) {
        try {
            long startSeconds = Instant.now().getEpochSecond();
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray resultArray = jsonObject.getAsJsonObject("quoteResponse").getAsJsonArray("result");
            if (resultArray.size() > 0) {
                JsonObject stockInfo = resultArray.get(0).getAsJsonObject();
                BigDecimal fiftyTwoWeekLow = stockInfo.get("fiftyTwoWeekLow").getAsBigDecimal();
                BigDecimal fiftyTwoWeekHigh = stockInfo.get("fiftyTwoWeekHigh").getAsBigDecimal();
                BigDecimal fiftyTwoWeekChangePercent = stockInfo.get("fiftyTwoWeekChangePercent").getAsBigDecimal();
                BigDecimal fiftyDayAverage = stockInfo.get("fiftyDayAverage").getAsBigDecimal();
                BigDecimal twoHundredDayAverage = stockInfo.get("twoHundredDayAverage").getAsBigDecimal();
                String symbol = stockInfo.get("symbol").getAsString();

                Instant timestamp = Instant.now();

                log.info(fiftyDayAverage.toString());
//                StockData stockData = new StockData(fiftyTwoWeekLow, fiftyTwoWeekHigh, fiftyTwoWeekChangePercent, fiftyDayAverage, twoHundredDayAverage, symbol, timestamp);
                StockData stockData = new StockData(fiftyDayAverage, timestamp);
                stockDataQueue.offer(stockData);

                // Add the stockData to the queue, in the form (timestamp, price)
//                ArrayList<Object> stockData = new ArrayList<>();
                // Print the stockData
                System.out.println(stockData);
                // Update the chart on the JavaFX Application Thread
                Platform.runLater(() -> {
                    long currSeconds = Instant.now().getEpochSecond();
                    long secSinceStart = currSeconds - startSeconds;
                    series.getData().add(new XYChart.Data<>(secSinceStart, fiftyDayAverage.doubleValue()));
                });

                log.info("Stored Stock Data: {}", stockData);
            }
        } catch (Exception e) {
            log.error("Failed to parse and store stock data", e);
        }
    }

    private static <T> T fetchWithRetry(FetchOperation<T> operation) throws IOException {
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                return operation.fetch();
            } catch (IOException e) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw e;
                }
                long backoffMs = INITIAL_BACKOFF_MS * (long) Math.pow(2, retries - 1);
                log.info("Retrying in {}ms...", backoffMs);
                try {
                    TimeUnit.MILLISECONDS.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry", ie);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Operation interrupted", e);
            }
        }
        throw new IOException("Max retries reached");
    }

    @FunctionalInterface
    private interface FetchOperation<T> {
        T fetch() throws IOException, InterruptedException;
    }

    static class StockData {
        private final BigDecimal price;
        private final Instant timestamp;

        public StockData(BigDecimal price, Instant timestamp) {
            this.price = price;
            this.timestamp = timestamp;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return "StockData{" +
                    "price=" + price +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
