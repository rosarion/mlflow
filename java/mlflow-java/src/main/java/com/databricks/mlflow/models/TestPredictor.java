package com.databricks.mlflow.models;

import com.databricks.mlflow.sagemaker.SageMakerServer;

import java.util.Optional;

class TestPredictor extends Predictor {
    @Override
    public String predict(String input) {
        System.out.println(input);
        return "sample_response";
    }

    public static void main(String[] args) {
        TestPredictor predictor = new TestPredictor();
        SageMakerServer.serve(predictor, Optional.<Integer>empty());
    }
}
