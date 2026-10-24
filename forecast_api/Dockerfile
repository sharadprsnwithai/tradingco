FROM python:3.12-slim
WORKDIR /app
COPY forecast_service.py .
RUN pip install flask yfinance pytorch-forecasting
CMD ["python", "forecast_service.py"]
