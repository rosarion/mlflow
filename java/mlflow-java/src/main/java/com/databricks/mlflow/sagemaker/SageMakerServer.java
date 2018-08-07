package com.databricks.mlflow.sagemaker;

import com.databricks.mlflow.LoaderModuleException;

import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import spark.Request;
import spark.Response;
import static spark.Spark.*;

public class SageMakerServer {
    public static final String RESPONSE_KEY_ERROR_MESSAGE = "Error";

    private static final int HTTP_RESPONSE_CODE_SERVER_ERROR = 500;
    private static final int HTTP_RESPONSE_CODE_SUCCESS = 200;

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

    private static final int DEFAULT_PORT = 8080;

    public static void serve(Predictor predictor, Optional<Integer> portNumber) {
        serve(Optional.of(predictor), portNumber);
    }

    public static void serve(String modelPath, Optional<Integer> portNumber) {
        Optional<Predictor> predictor = Optional.empty();
        try {
            predictor = Optional.of(JavaFunc.load(modelPath, Optional.<String>empty()));
        } catch (InstantiationException | InvocationTargetException | LoaderModuleException
            | IOException e) {
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
                // TODO: Do something case-specific
                DataFrame parsedInput = DataFrame.fromJson(request.body());
                DataFrame result = predictor.predict(parsedInput);
                return result.toJson();
            }
            case Csv: {
                // TODO: Do something case-specific
                DataFrame parsedInput = DataFrame.fromCsv(request.body());
                DataFrame result = predictor.predict(parsedInput);
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

    public static void main(String[] args) {
        String modelPath = args[0];
        Optional<Integer> portNum = Optional.empty();
        if (args.length > 1) {
            portNum = Optional.of(Integer.parseInt(args[2]));
        }
        serve(modelPath, portNum);
    }
}
