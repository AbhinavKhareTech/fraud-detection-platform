# ADR-001: XGBoost over Neural Networks for Card-Present Fraud Scoring

## Status
Accepted

## Context
The fraud scoring service needs an ML model for card-present (CP) transaction scoring. The model sits in the payment authorization path with a strict 50ms total latency budget (including feature retrieval, inference, and response serialization). Two model families were evaluated: gradient-boosted decision trees (XGBoost/LightGBM) and neural networks (MLP, LSTM, Transformer).

## Decision
Use XGBoost exported to ONNX format for CP fraud scoring.

## Rationale

### Inference latency
XGBoost inference on a 17-feature vector completes in 1-3ms on CPU via ONNX Runtime. A comparable MLP requires 5-15ms, and sequence models (LSTM/Transformer) require 20-50ms due to attention computation. With our 50ms total budget and 5ms feature store read, the remaining 40ms must cover inference, serialization, and network overhead. XGBoost provides the largest safety margin.

### Latency variance
Tree models have near-zero variance in inference time. Every prediction traverses the same number of tree nodes. Neural networks have variable computation depending on input characteristics, especially sequence models where input length affects latency. In a payment authorization path, latency variance is as dangerous as high latency because p99 spikes cause terminal timeouts.

### Explainability
XGBoost produces native feature importance scores that map directly to human-readable reason codes. When a transaction is declined, the reason codes are preserved for chargeback representment evidence and regulatory audit trails. Neural networks require post-hoc explanation methods (SHAP, LIME) that add latency and produce less stable explanations.

### JVM embedding
ONNX Runtime has a mature Java SDK, allowing the model to run inside the Spring Boot JVM without a separate Python inference server. This eliminates an inter-process network hop and a deployment dependency. Neural network serving (TorchServe, TF Serving) would require a separate containerized service.

### Training data characteristics
Restaurant payment fraud data is tabular with well-defined features. Tree models excel on tabular data. Neural networks show advantages primarily on unstructured data (images, text, sequences) or when feature interactions are extremely complex. For our 17-feature tabular problem, XGBoost matches or exceeds neural network accuracy.

## Consequences
- The ML pipeline must export models to ONNX format after training
- The scoring service includes ONNX Runtime as a JVM dependency (~50MB)
- Sequence-based features (transaction history patterns) must be pre-computed as aggregate features rather than fed as raw sequences to the model
- If future accuracy requirements demand sequence models, they can be deployed for CNP scoring (200ms budget) while CP retains the tree model

## Alternatives Considered
- **LightGBM**: Similar performance to XGBoost but less mature ONNX export. Would reconsider if ONNX support improves.
- **CatBoost**: Strong on categorical features but heavier inference. Not justified for our feature set.
- **Rule engine only (no ML)**: Simpler but cannot capture complex feature interactions. We use the rule engine as primary for CP with ML in shadow mode, with a path to ML-primary once validated.
