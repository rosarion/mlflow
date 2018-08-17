package org.mlflow.sagemaker;

import org.mlflow.sagemaker.PredictorLoadingException;
import org.mlflow.mleap.MLeapLoader;
import org.mlflow.models.Model;

import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import spark.Request;
import spark.Response;
import static spark.Spark.*;

/**
 * A RESTful webserver for {@link Predictor Predictors}
 */
public class SageMakerServer {
    public static final String RESPONSE_KEY_ERROR_MESSAGE = "Error";

    private static final int HTTP_RESPONSE_CODE_SERVER_ERROR = 500;
    private static final int HTTP_RESPONSE_CODE_SUCCESS = 200;
    private static final int DEFAULT_PORT = 8080;

    private enum RequestContentType {
        Csv("text/csv"),
        Json("application/json"),
        Invalid("invalid");

        private final String value;
        private static final Map<String, RequestContentType> BY_VALUE_MAP = new HashMap<>();
        static {
            for (RequestContentType inputType : RequestContentType.values()) {
                BY_VALUE_MAP.put(inputType.value, inputType);
            }
        }

        RequestContentType(String value) {
            this.value = value;
        }

        public static RequestContentType fromValue(String value) {
            if (BY_VALUE_MAP.containsKey(value)) {
                return BY_VALUE_MAP.get(value);
            } else {
                return RequestContentType.Invalid;
            }
        }
    }

    /**
     * Serves the specified predictor locally on the specified port.
     * If no port is specified, the default port is used
     */
    public static void serve(Predictor predictor, Optional<Integer> portNumber) {
        serve(Optional.of(predictor), portNumber);
    }

    /**
     * Loads the MLFLow model at the specified path as an {@link MLeapPredictor}
     * and serves it on the specified port
     */
    public static void serve(String modelPath, Optional<Integer> portNumber) {
        Optional<Predictor> predictor = Optional.empty();
        try {
            Model config = Model.fromRootPath(modelPath);
            predictor = Optional.of((new MLeapLoader()).load(config));
        } catch (PredictorLoadingException | IOException e) {
            e.printStackTrace();
        }
        serve(predictor, portNumber);
    }

    private static void serve(Optional<Predictor> predictor, Optional<Integer> portNumber) {
        port(portNumber.orElse(DEFAULT_PORT));
        get("/ping", (request, response) -> {
            if (!predictor.isPresent()) {
                return yieldMissingPredictorError(response);
            }
            response.status(200);
            return "";
        });

        post("/invocations", (request, response) -> {
            if (!predictor.isPresent()) {
                return yieldMissingPredictorError(response);
            }

            try {
                String result = evaluateRequest(predictor.get(), request);
                response.status(HTTP_RESPONSE_CODE_SUCCESS);
                return result;
            } catch (PredictorEvaluationException e) {
                response.status(HTTP_RESPONSE_CODE_SERVER_ERROR);
                String errorMessage = e.getMessage();
                return getErrorResponseJson(errorMessage);
            } catch (Exception e) {
                e.printStackTrace();
                response.status(HTTP_RESPONSE_CODE_SERVER_ERROR);
                String errorMessage = "An unknown error occurred while evaluating the model!";
                return getErrorResponseJson(errorMessage);
            }
        });
    }

    private static String yieldMissingPredictorError(Response response) {
        response.status(HTTP_RESPONSE_CODE_SERVER_ERROR);
        return getErrorResponseJson("Error loading predictor! See container logs for details!");
    }

    private static String evaluateRequest(Predictor predictor, Request request)
        throws PredictorEvaluationException {
        RequestContentType inputType = RequestContentType.fromValue(request.contentType());
        switch (inputType) {
            case Json: {
                Optional<DataFrame> parsedInput = Optional.<DataFrame>empty();
                try {
                    parsedInput = Optional.of(DataFrame.fromJson(request.body()));
                } catch (UnsupportedOperationException e) {
                    throw new PredictorEvaluationException(
                        "This model does not yet support evaluating JSON inputs.");
                }
                DataFrame result = predictor.predict(parsedInput.get());
                return result.toJson();
            }
            case Csv: {
                Optional<DataFrame> parsedInput = Optional.<DataFrame>empty();
                try {
                    parsedInput = Optional.of(DataFrame.fromCsv(request.body()));
                } catch (UnsupportedOperationException e) {
                    throw new PredictorEvaluationException(
                        "This model does not yet support evaluating CSV inputs.");
                }
                DataFrame result = predictor.predict(parsedInput.get());
                return result.toCsv();
            }
            case Invalid:
            default:
                throw new UnsupportedContentTypeException(request.contentType());
        }
    }

    private static String getErrorResponseJson(String errorMessage) {
        String response =
            String.format("{ \"%s\" : \"%s\" }", RESPONSE_KEY_ERROR_MESSAGE, errorMessage);
        return response;
    }

    static class UnsupportedContentTypeException extends PredictorEvaluationException {
        protected UnsupportedContentTypeException(String contentType) {
            super(String.format("Unsupported request input type: %s", contentType));
        }
    }

    /**
     * Entrypoint for locally serving MLFlow models with the MLeap flavor using the
     * {@link SageMakerServer}
     *
     * This entrypoint expects the following arguments:
     * 1. The path to the MLFlow model to serve. This model must have the MLeap flavor.
     * 2. (Optional) the number of the port on which to serve the MLFlow model.
     */
    public static void main(String[] args) {
        String modelPath = args[0];
        Optional<Integer> portNum = Optional.empty();
        if (args.length > 1) {
            portNum = Optional.of(Integer.parseInt(args[2]));
        }
        serve(modelPath, portNum);
    }
}
