import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/*
cmd: pbench -t 100 -d 10 -q 300 -v 3 -l 10 http://localhost/test?@{p1}=v1&p2=v2
-t: test time in seconds
-d: send request in ms of one second, value range [1, 1000]
-q: QPS
-v: view response content
-l: print info every N loops
 */
public class PBench {
  private int loops = 5;  // store -t param value
  private int qps = 300;  // store -q param value
  private int qps_delay = 1000;   // store -d param value
  private int print_loops = 10;   // store -l param value
  private String url;
  private int requestId;  // sequence requestId
  // requestId -> response time
  private ConcurrentHashMap<Integer, Long> responseTimeMap = new ConcurrentHashMap<Integer, Long>(1000);
  private AtomicLong responseDelayTime = new AtomicLong();

  private CloseableHttpAsyncClient httpclient;

  private AtomicInteger viewResult = new AtomicInteger(2);

  private AtomicInteger completed_ok = new AtomicInteger();
  private AtomicInteger completed_fail = new AtomicInteger();
  private AtomicInteger future_failed = new AtomicInteger();
  private AtomicInteger future_cancelled = new AtomicInteger();

  private AtomicLong receive_bytes = new AtomicLong();

  private long testStartTime;

  private final Map<String, List<String>> paramMap = new HashMap<String, List<String>>();

  private final String paramFile = "param.txt"; //replaced param input file

  private final Map<String, String> paramConstantName = Maps.newHashMap();
  private final Map<String, String> paramReplaceName = Maps.newHashMap();


  public static void main(String[] argv) {
    PBench pBench = new PBench();
    pBench.config(argv);
    pBench.start();
  }

  public void config(String[] argv) {
    for (int i = 0; i < argv.length; i++) {
      String cmd = argv[i];
      String value;
      if (cmd.equals("-t")) {
        value = argv[++i];
        loops = Integer.parseInt(value);
      } else if (cmd.equals("-q")) {
        value = argv[++i];
        qps = Integer.parseInt(value);
      } else if (cmd.equals("-d")) {
        value = argv[++i];
        qps_delay = Integer.parseInt(value);
        if (qps_delay < 1 || qps_delay > 1000) {
          println("-d range [1,1000]");
          System.exit(-1);
        }
      } else if (cmd.equals("-v")) {
        value = argv[++i];
        viewResult.set(Integer.parseInt(value));
      } else if (cmd.equals("-l")) {
        value = argv[++i];
        print_loops = Integer.parseInt(value);
      } else if (cmd.startsWith("http")) {
        url = cmd;
      } else if (cmd.startsWith("\"http") && cmd.endsWith("\"")) {
        cmd = cmd.substring(1, cmd.length() - 1);
        url = cmd;
      } else {
        println("Unknown Arg: " + cmd);
        System.exit(-1);
      }
    }
    Preconditions.checkArgument(url != null, "url can't be null");

    String[] urlParts = url.split("\\?");
    println(Arrays.toString(urlParts));
    url = urlParts[0];
    if (urlParts.length == 2) {
      String[] paramParts = urlParts[1].split("&");
      for (String paramPart : paramParts) {
        String[] paramPair = paramPart.split("=");
        String paramName = paramPair[0].trim();
        if (paramName.startsWith("@{")) {
          String trueParamName = paramName.substring(2);
          trueParamName = trueParamName.substring(0, trueParamName.length() - 1).trim();
          paramReplaceName.put(trueParamName, null);
        } else {
          paramConstantName.put(paramName, paramPair[1].trim());
        }
      }

      parseParamFile(paramReplaceName.keySet());
    }
    paramConstantName.put("chaos_monkey", "true");

  }

  private void parseParamFile(Collection<String> params) {
    try {
      if (params == null || params.size() == 0) {
        return;
      }
      File file = new File(paramFile);
      if (!file.exists() || !file.canRead() || !file.isFile()) {
        println("Make sure the param file: " + paramFile + " exist");
        System.exit(-1);
      }
      Scanner in = new Scanner(new BufferedInputStream(new FileInputStream(file)));
      Preconditions.checkArgument(in.hasNextLine(), "Lack first param name line");
      String line = in.nextLine();
      String[] paramNames = line.split("[\t| ]+");
      Set<String> paramSet = Sets.newHashSet(paramNames);
      for (String paramName : params) {
        Preconditions.checkArgument(paramSet.contains(paramName), paramFile + " not include param: " + paramName);
        paramMap.put(paramName, Lists.<String>newArrayList());
      }
      while (in.hasNextLine()) {
        line = in.nextLine();
        if (Strings.isNullOrEmpty(line)) {
          continue;
        }
        String[] values = line.split("[\t| ]+");
        Preconditions.checkArgument(values.length == paramNames.length, "param value number mismatch");
        for (int i = 0; i < values.length; i++) {
          if (params.contains(paramNames[i])) {
            paramMap.get(paramNames[i]).add(values[i]);
          }
        }
      }
      in.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void println(String str) {
    System.out.println(str);
  }

  public void start() {
    println("Test start time: " + getTime());
    httpclient = getClient();
    httpclient.start();

    final int qps_times = 1000 / qps_delay;
    final int qps_per_time = qps / qps_times;
    final int qps_remain = qps - qps_per_time * qps_times;

    long loop_start = System.currentTimeMillis();
    long receive_bytes_0 = receive_bytes.get();
    int send_requests_0 = responseTimeMap.size();
    testStartTime = System.currentTimeMillis();
    int completed_ok_0 = completed_ok.get();
    int completed_fail_0 = completed_fail.get();
    int future_fail_0 = future_failed.get();
    int future_cancel_0 = future_cancelled.get();
    for (int i = 0; i < loops ; i++) { // one loop one second
      for (int j = 0; j < qps_times; j++) {
        final long start = System.currentTimeMillis();
        for (int k = 0; k < qps_per_time; k++) {
          HttpGet request = new HttpGet(genGetFullUrl(requestId));
          FutureCallback<HttpResponse> callback = new ResponseCallBack(requestId++, start);
          httpclient.execute(request, callback);
        }
        sleep(1000 / qps_times);
      }
      HttpGet request = new HttpGet(genGetFullUrl(requestId));
      for (int k = 0; k < qps_remain; k++) {
        FutureCallback<HttpResponse> callback = new ResponseCallBack(requestId++, System.currentTimeMillis());
        httpclient.execute(request, callback);
      }
      if (i % print_loops == (print_loops - 1)) {
        double time = (System.currentTimeMillis() - loop_start) / 1000.0;
        if (time == 0.0) {
          time = 1.0;
        }
        println(String.format("Time: %s TPS: %d, Delay: %dms, Rate: %.3f MB/s,  OK: %d, Fail: %d, Exception: %d, Cancel: %d ", getTime(),
            (int)((responseTimeMap.size() - send_requests_0) / time), responseDelayTime.get() / (responseTimeMap.size() == 0 ? 1 : responseTimeMap.size()),
            (receive_bytes.get() - receive_bytes_0) / 1024.0 / 1024.0 / time,
            completed_ok.get() - completed_ok_0, completed_fail.get() - completed_fail_0,
            future_failed.get() - future_fail_0, future_cancelled.get() - future_cancel_0
        ));
        loop_start = System.currentTimeMillis();
        receive_bytes_0 = receive_bytes.get();
        send_requests_0 = responseTimeMap.size();
        completed_ok_0 = completed_ok.get();
        completed_fail_0 = completed_fail.get();
        future_fail_0 = future_failed.get();
        future_cancel_0 = future_cancelled.get();
      }
    }

    while (responseTimeMap.size() < requestId) {
      println(getTime() + ": responses=" + responseTimeMap.size());
      sleep(1000);
    }

    calcResult();

    try {
      httpclient.close();
    } catch (IOException ignore) {

    }

  }

  private String genGetFullUrl(int requestId) {
    StringBuilder sb = new StringBuilder(url);
    sb.append("?");
    for (Map.Entry<String, String> entry : paramConstantName.entrySet()) {
      sb.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
    }
    for (Map.Entry<String, String> entry : paramReplaceName.entrySet()) {
      int i = requestId % paramMap.values().iterator().next().size();
      sb.append(entry.getKey()).append('=').append(paramMap.get(entry.getKey()).get(i)).append('&');
    }
    return sb.toString();
  }

  private String getTime() {
    return DateUtils.formatDate(new Date(), "yyyy-MM-dd HH:mm:ss");
  }

  private void calcResult() {
    long testEndTime = System.currentTimeMillis();
    println(String.format("Test end time: %s  Test Duration: %.1fs", getTime(), (System.currentTimeMillis() - testStartTime) / 1000.0));
    println(String.format("Total Request %d, total responses %d", requestId, responseTimeMap.size()));
    println(String.format("QPS: %d  TPS: %d", qps, (int)(requestId / ((testEndTime - testStartTime) / 1000.0))));
    println(String.format("Receive %.1f MB, Rate %.3f MB/s", receive_bytes.get() / 1024.0 / 1024.0, receive_bytes.get() / 1024.0 / 1024.0 / ((System.currentTimeMillis() - testStartTime) / 1000.0)));
    println(String.format("Completed OK=%d fail=%d, Exception=%d, Cancelled=%d", completed_ok.get(), completed_fail.get(), future_failed.get(), future_cancelled.get()));

    Long total = 0L;
    for (Long l : responseTimeMap.values()) {
      total += l;
    }
    println(String.format("Average Delay: %dms", (total / responseTimeMap.size())));
    TreeSet<Long> sortSet = new TreeSet<Long>(responseTimeMap.values());
    long[] percentiles = new long[6];
    final int size = sortSet.size();
    Long[] sorted = sortSet.toArray(new Long[size]);
    percentiles[0] = sorted[(int)(size * 0.50)];
    percentiles[1] = sorted[(int)(size * 0.75)];
    percentiles[2] = sorted[(int)(size * 0.90)];
    percentiles[3] = sorted[(int)(size * 0.95)];
    percentiles[4] = sorted[(int)(size * 0.99)];
    percentiles[5] = sorted[(int)(size * 0.999)];
    println(String.format("Response Percentiles(0.50,0.75,0.90,0.95,0.99,0.999): %d %d %d %d %d %d", percentiles[0],
        percentiles[1],percentiles[2],percentiles[3],percentiles[4],percentiles[5]));

  }

  private void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (Exception i) {}
  }


  private static CloseableHttpAsyncClient getClient() {
    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(3000)
        .setSocketTimeout(3000)
        .setConnectionRequestTimeout(1000)
        .build();

    //配置io线程
    IOReactorConfig ioReactorConfig = IOReactorConfig.custom().
        setIoThreadCount(Runtime.getRuntime().availableProcessors())
        .setSoKeepAlive(true)
        .build();
    //设置连接池大小
    ConnectingIOReactor ioReactor=null;
    try {
      ioReactor = new DefaultConnectingIOReactor(ioReactorConfig);
    } catch (IOReactorException e) {
      e.printStackTrace();
    }
    PoolingNHttpClientConnectionManager connManager = new PoolingNHttpClientConnectionManager(ioReactor);
    connManager.setMaxTotal(3000);
    connManager.setDefaultMaxPerRoute(1000);


    CloseableHttpAsyncClient httpclient = HttpAsyncClients.custom().
        setConnectionManager(connManager)
        .setDefaultRequestConfig(requestConfig)
        .build();

    return httpclient;
  }

  class ResponseCallBack implements FutureCallback<HttpResponse> {
    private final int requestId;
    private final long startTime;

    public ResponseCallBack(int requestId, long startTime) {
      this.requestId = requestId;
      this.startTime = startTime;
    }

    public void completed(final HttpResponse response) {
      try {
        recordTime();
        if (response.getStatusLine().getStatusCode() == 200) {
          completed_ok.incrementAndGet();
        } else {
          completed_fail.incrementAndGet();
        }
        String content = EntityUtils.toString(response.getEntity(), "UTF-8");
        receive_bytes.addAndGet(content != null ? content.length() : 0);
        if (viewResult.get() >= 0 && viewResult.decrementAndGet() >= 0) {
          println(content);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    public void failed(final Exception e) {
      recordTime();
      future_failed.incrementAndGet();
    }

    public void cancelled() {
      recordTime();
      future_cancelled.incrementAndGet();
    }

    private void recordTime() {
      long delay = System.currentTimeMillis() - startTime;
      responseDelayTime.addAndGet(delay);
      responseTimeMap.put(this.requestId, delay);
    }
  }


}

