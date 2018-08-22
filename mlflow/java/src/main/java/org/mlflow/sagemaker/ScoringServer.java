package org.mlflow.sagemaker;

import java.io.IOException;
import java.util.Optional;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.mlflow.mleap.MLeapLoader;
import org.mlflow.models.Model;
import org.mlflow.utils.Environment;
import org.mlflow.utils.FileUtils;
import org.mlflow.utils.SystemEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A RESTful webserver for {@link Predictor Predictors} that runs on the local host */
public class ScoringServer {
  public static final String RESPONSE_KEY_ERROR_MESSAGE = "Error";
  private static final String REQUEST_CONTENT_TYPE_JSON = "application/json";
  private static final String REQUEST_CONTENT_TYPE_CSV = "text/csv";

  private static final Logger logger = LoggerFactory.getLogger(ScoringServer.class);
  private final Server server;
  private final ServerConnector httpConnector;

  /**
   * Constructs a {@link ScoringServer} to serve the specified {@link Predictor} on the local host
   * on a randomly-selected available port
   */
  public ScoringServer(Predictor predictor) {
    ServerThreadConfiguration threadConfiguration =
        ServerThreadConfiguration.create(SystemEnvironment.get());
    this.server =
        new Server(
            new QueuedThreadPool(
                threadConfiguration.getMaxThreads(), threadConfiguration.getMinThreads()));
    this.server.setStopAtShutdown(true);

    this.httpConnector = new ServerConnector(this.server, new HttpConnectionFactory());
    this.server.addConnector(this.httpConnector);

    ServletContextHandler rootContextHandler = new ServletContextHandler(null, "/");
    rootContextHandler.addServlet(new ServletHolder(new ScoringServer.PingServlet()), "/ping");
    rootContextHandler.addServlet(
        new ServletHolder(new ScoringServer.InvocationsServlet(predictor)), "/invocations");
    this.server.setHandler(rootContextHandler);
  }

  /**
   * Loads the MLFlow model at the specified path as a {@link Predictor} and serves it on the local
   * host at the specified port
   *
   * @param modelPath The path to the MLFlow model to serve
   */
  public ScoringServer(String modelPath) throws PredictorLoadingException {
    this(loadPredictorFromPath(modelPath));
  }

  private static Predictor loadPredictorFromPath(String modelPath)
      throws PredictorLoadingException {
    Model config = null;
    try {
      config = Model.fromRootPath(modelPath);
    } catch (IOException e) {
      throw new PredictorLoadingException(
          "Failed to load the configuration for the MLFlow model at the specified path.");
    }
    return (new MLeapLoader()).load(config);
  }

  /**
   * Starts the scoring server locally on a randomly-selected, available port
   *
   * @throws IllegalStateException If the server is already active on another port
   * @throws ServerStateChangeException If the server failed to start and was inactive prior to the
   *     invocation of this method
   */
  public void start() {
    // Setting port zero instructs Jetty to select a random port
    start(0);
  }

  /**
   * Starts the scoring server locally on the specified port
   *
   * @throws IllegalStateException If the server is already active on another port
   * @throws ServerStateChangeException If the server failed to start and was inactive prior to the
   *     invocation of this method
   */
  public void start(int portNumber) {
    if (isActive()) {
      int activePort = this.httpConnector.getLocalPort();
      throw new IllegalStateException(
          String.format(
              "Attempted to start a server that is already active on port %d", activePort));
    }

    this.httpConnector.setPort(portNumber);
    try {
      this.server.start();
    } catch (Exception e) {
      throw new ServerStateChangeException(e);
    }
    logger.info(String.format("Started scoring server on port: %d", portNumber));
  }

  /**
   * Stops the scoring server
   *
   * @throws IllegalStateException If the server is already active on another port
   * @throws ServerStateChangeException If the server failed to start and was inactive prior to the
   *     invocation of this method
   */
  public void stop() {
    try {
      this.server.stop();
      this.server.join();
    } catch (Exception e) {
      throw new ServerStateChangeException(e);
    }
    logger.info("Stopped the scoring server successfully.");
  }

  /** @return `true` if the server is active (running), `false` otherwise */
  public boolean isActive() {
    return this.server.isStarted();
  }

  /**
   * @return Optional that either: - Contains the port on which the server is running, if the server
   *     is active - Is empty, if the server is not active
   */
  public Optional<Integer> getPort() {
    int boundPort = this.httpConnector.getLocalPort();
    if (boundPort >= 0) {
      return Optional.of(boundPort);
    } else {
      // The server connector port request returned an error code
      return Optional.empty();
    }
  }

  public static class ServerStateChangeException extends RuntimeException {
    ServerStateChangeException(Exception e) {
      super(e.getMessage());
    }
  }

  /**
   * Configuration providing the minimum and maximum number of threads to allocate to the scoring
   * server
   */
  static class ServerThreadConfiguration {
    static final String ENV_VAR_MINIMUM_SERVER_THREADS = "SCORING_SERVER_MIN_THREADS";
    static final String ENV_VAR_MAXIMUM_SERVER_THREADS = "SCORING_SERVER_MAX_THREADS";

    static final int DEFAULT_MINIMUM_SERVER_THREADS = 1;
    // Assuming an 8 core machine with hyperthreading
    static final int DEFAULT_MAXIMUM_SERVER_THREADS = 16;

    private final int minThreads;
    private final int maxThreads;

    private ServerThreadConfiguration(int minThreads, int maxThreads) {
      this.minThreads = minThreads;
      this.maxThreads = maxThreads;
    }

    static ServerThreadConfiguration create(Environment environment) {
      int minThreads =
          environment.getIntegerValue(
              ENV_VAR_MINIMUM_SERVER_THREADS, DEFAULT_MINIMUM_SERVER_THREADS);
      int maxThreads =
          environment.getIntegerValue(
              ENV_VAR_MAXIMUM_SERVER_THREADS, DEFAULT_MAXIMUM_SERVER_THREADS);
      return new ServerThreadConfiguration(minThreads, maxThreads);
    }

    /** @return The minimum number of server threads specified by the configuration */
    int getMinThreads() {
      return this.minThreads;
    }

    /** @return The maximum number of server threads specified by the configuration */
    int getMaxThreads() {
      return this.maxThreads;
    }
  }

  static class PingServlet extends HttpServlet {
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) {
      response.setStatus(HttpServletResponse.SC_OK);
    }
  }

  static class InvocationsServlet extends HttpServlet {
    private final Predictor predictor;

    InvocationsServlet(Predictor predictor) {
      this.predictor = predictor;
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      String requestContentType = request.getHeader("Content-type");
      String requestBody = FileUtils.readInputStreamAsUtf8(request.getInputStream());
      String responseContent = null;
      try {
        responseContent = evaluateRequest(requestBody, requestContentType);
      } catch (PredictorEvaluationException e) {
        logger.error("Encountered a failure when evaluating the predictor.", e);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        responseContent = getErrorResponseJson(e.getMessage());
      } catch (InvalidRequestTypeException e) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      } catch (Exception e) {
        logger.error("An unknown error occurred while evaluating the prediction request.", e);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        responseContent =
            getErrorResponseJson("An unknown error occurred while evaluating the model!");
      } finally {
        response.getWriter().print(responseContent);
        response.getWriter().close();
      }
    }

    private String evaluateRequest(String requestContent, String requestContentType)
        throws PredictorEvaluationException, InvalidRequestTypeException {
      PredictorDataWrapper predictorInput = null;
      if (requestContentType == REQUEST_CONTENT_TYPE_JSON) {
        predictorInput =
            new PredictorDataWrapper(requestContent, PredictorDataWrapper.ContentType.Json);
        PredictorDataWrapper result = predictor.predict(predictorInput);
        return result.toJson();
      } else if (requestContentType == REQUEST_CONTENT_TYPE_CSV) {
        predictorInput =
            new PredictorDataWrapper(requestContent, PredictorDataWrapper.ContentType.Csv);
        PredictorDataWrapper result = predictor.predict(predictorInput);
        return result.toCsv();
      } else {
        logger.error(
            String.format(
                "Received a request with an unsupported content type: %s", requestContentType));
        throw new InvalidRequestTypeException(
            "Invocations content must be of content type `application/json` or `text/csv`");
      }
    }

    private String getErrorResponseJson(String errorMessage) {
      String response =
          String.format("{ \"%s\" : \"%s\" }", RESPONSE_KEY_ERROR_MESSAGE, errorMessage);
      return response;
    }

    static class InvalidRequestTypeException extends Exception {
      InvalidRequestTypeException(String message) {
        super(message);
      }
    }
  }

  /**
   * Entrypoint for locally serving MLFlow models with the MLeap flavor using the {@link
   * ScoringServer}
   *
   * <p>This entrypoint expects the following arguments: 1. The path to the MLFlow model to serve.
   * This model must have the MLeap flavor. 2. (Optional) the number of the port on which to serve
   * the MLFlow model.
   */
  public static void main(String[] args) throws IOException, PredictorLoadingException {
    String modelPath = args[0];
    Optional<Integer> portNum = Optional.empty();
    if (args.length > 1) {
      portNum = Optional.of(Integer.parseInt(args[2]));
    }
    ScoringServer server = new ScoringServer(modelPath);
    try {
      server.start(8080);
    } catch (ServerStateChangeException e) {
      logger.error("Encountered an error while starting the prediction server.", e);
    }
  }
}
