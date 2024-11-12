# module2/day1/examples/analyzeData.py

import requests
import time
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

# Calculate Simple Statistics
# Calculate basic statistics like the mean, median, or standard deviation of Bitcoin prices.
def calculate_statistics(real_time_data):
    # Extract prices into a list
    buy_prices = [details['buy'] for details in real_time_data.values()]
    
    # Calculate and print statistics
    mean_price = np.mean(buy_prices)
    median_price = np.median(buy_prices)
    std_dev_price = np.std(buy_prices)

    print(f"Mean Buy Price: {mean_price}")
    print(f"Median Buy Price: {median_price}")
    print(f"Standard Deviation of Buy Prices: {std_dev_price}")




# Track Price Changes Over Time
# Modify the polling function to track how prices change over time and then visualize it.
prices_over_time = {}

def track_price_changes(real_time_data, prices_over_time):
    # Track the latest buy price for each currency
    for currency, details in real_time_data.items():
        if currency not in prices_over_time:
            prices_over_time[currency] = []
        prices_over_time[currency].append(details['buy'])

    # Optionally, limit the history size to the last N prices
    history_size = 5
    for currency in prices_over_time:
        prices_over_time[currency] = prices_over_time[currency][-history_size:]

    # Print the tracked prices for each currency in a visually appealing way
    for currency, prices in prices_over_time.items():
        price_changes_str = " -> ".join([f"{price:,.2f}" for price in prices])
        print(f"{currency}: {price_changes_str}")




# Function to poll an API every 10 seconds for new data
def poll_api(api_url):
    prices_over_time = {}  # Initialize a dictionary to track prices over time
    
    while True:
        response = requests.get(api_url)
        if response.status_code == 200:
            real_time_data = response.json()

            print("------------------------------------------------------")
            print('New data received:', real_time_data)
            print("------------------------------------------------------")

            # Call the function to calculate statistics
            calculate_statistics(real_time_data)
            
            # Update the prices over time and the price trends
            track_price_changes(real_time_data, prices_over_time)
            
            
            time.sleep(10)  # Wait for 10 seconds before polling again
        else:
            print('Error fetching data:', response.status_code)

# URL of the real-time data API
real_time_api_url = 'https://blockchain.info/ticker'

# Start polling the API
poll_api(real_time_api_url)