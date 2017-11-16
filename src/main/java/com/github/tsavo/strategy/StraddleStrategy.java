package com.github.tsavo.strategy;

import com.github.tsavo.traderplan.exchange.Exchange;
import com.github.tsavo.traderplan.exchange.Order;
import com.tsavo.trade.database.Reporter;
import com.tsavo.trade.database.model.Straddle;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Created by evilg on 9/29/2017.
 */
public class StraddleStrategy implements Strategy {
    public Exchange exchange;
    public Straddle straddle;
    public Reporter reporter;
    public CurrencyPair currencyPair;
    public Order order;
    public boolean running = true;

    public StraddleStrategy(Exchange exchange, Straddle straddle, Reporter discordChannelReportingStrategy) {
        this.exchange = exchange;
        this.straddle = straddle;
        this.reporter = discordChannelReportingStrategy;
        this.currencyPair = straddle.currencyPair.toCurrencyPair();
    }

    @Override
    public void findOpportunities() throws ExchangeException, NotAvailableFromExchangeException, NotYetImplementedForExchangeException, IOException {

        BigDecimal balance;
        balance = exchange.getBalance(currencyPair.base);
        List<LimitOrder> lowestAsks = exchange.getLowestAsks(currencyPair);
        if (lowestAsks.size() == 0) {
            return;
        }
        LimitOrder lowestAsk = lowestAsks.get(0);
        List<LimitOrder> orders = exchange.getHighestBids(currencyPair);
        LimitOrder highestBid = orders.get(0);
        BigDecimal averagePrice = lowestAsk.getLimitPrice().add(highestBid.getLimitPrice()).divide(new BigDecimal(2), 8, BigDecimal.ROUND_HALF_EVEN);
        if (running && isTimeToBuy(balance, averagePrice)) {
            report("Straddle strategy for " + currencyPair + " is now in ALLOCATION.");

            BigDecimal targetPrice = averagePrice.multiply(new BigDecimal(1.1)).setScale(8, BigDecimal.ROUND_HALF_EVEN);

            BigDecimal tradeAmount = exchange.getAmountIn(straddle.amountToTrade, currencyPair);
            LimitOrder limitOrder = new LimitOrder.Builder(org.knowm.xchange.dto.Order.OrderType.BID, currencyPair).tradableAmount(tradeAmount).limitPrice(targetPrice).build();
            order = exchange.createTrailStopMarketMakerLimitOrder(limitOrder, limitOrder.getLimitPrice().divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).setScale(8, BigDecimal.ROUND_HALF_EVEN));
            try {
                order.openOrder();
            } catch (Exception e) {
                e.printStackTrace();
                report("The straddle strategy for " + currencyPair + " couldn't open the order for " + tradeAmount + "! Error from the exchange: " + e.getMessage());

                return;
            }

            if (!order.fillOrCancel()) {
                if (!running) {
                    report("Straddle strategy for " + currencyPair + " stopped.");
                    return;
                }
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            balance = exchange.getBalance(currencyPair.base);
            BigDecimal counterAmount = exchange.getBalance(currencyPair.counter);

            report("Straddle strategy spent " + order.order.getLimitPrice().multiply(order.order.getTradableAmount()).setScale(8, BigDecimal.ROUND_HALF_EVEN) + currencyPair.counter + " adding " + order.order.getTradableAmount().setScale(8, RoundingMode.HALF_EVEN) + "@" + order.order.getLimitPrice().setScale(8, RoundingMode.HALF_EVEN) + " of " + currencyPair.base + " on " + exchange + ". We now have " + balance.setScale(8, RoundingMode.HALF_EVEN) + " " + currencyPair.base + " and " + counterAmount.setScale(8, BigDecimal.ROUND_HALF_EVEN) + currencyPair.counter + " remaining.");

        }
        if (running && isTimeToSell(balance, averagePrice)) {
            report("Straddle strategy for " + currencyPair + " is now in DISTRIBUTION.");

            BigDecimal sellPrice = averagePrice.multiply(new BigDecimal(.9)).setScale(8, BigDecimal.ROUND_HALF_EVEN);
            BigDecimal tradeAmount = exchange.getAmountIn(straddle.amountToTrade, currencyPair);
            LimitOrder myOrder = new LimitOrder.Builder(org.knowm.xchange.dto.Order.OrderType.ASK, currencyPair).tradableAmount(tradeAmount).limitPrice(sellPrice).build();
            BigDecimal amountToTrailBy = myOrder.getLimitPrice().divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).setScale(8, BigDecimal.ROUND_HALF_EVEN);
            order = exchange.createTrailStopMarketMakerLimitOrder(myOrder, amountToTrailBy);
            try {
                order.openOrder();
            } catch (Exception e) {
                e.printStackTrace();
                report("The straddle strategy for " + currencyPair + " couldn't open the order for " + tradeAmount + "! Error from the exchange: " + e.getMessage());
                return;
            }
            if (!order.fillOrCancel()) {
                if (!running) {
                    report("Straddle strategy for " + currencyPair + " stopped.");
                    return;
                }
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            BigDecimal newBalance = exchange.getBalance(currencyPair.base);
            BigDecimal counterBalance = exchange.getBalance(currencyPair.counter).setScale(8, BigDecimal.ROUND_HALF_EVEN);

            report("Straddle strategy sold " + tradeAmount.setScale(8, RoundingMode.HALF_EVEN) + "@" + order.order.getLimitPrice() + " of " + currencyPair.base + " on " + exchange + ". We now have " + newBalance.setScale(8, RoundingMode.HALF_EVEN) + " " + currencyPair.base + " remaining and " + counterBalance + " " + currencyPair.counter + ".");

        }

    }

    public boolean isTimeToBuy(BigDecimal balance, BigDecimal averagePrice) {
        BigDecimal target = straddle.getTargetPercent(averagePrice).get();

        BigDecimal cap = BigDecimal.valueOf(Double.MAX_VALUE);
        if (straddle.maxAllocation.compareTo(BigDecimal.ZERO) > 0) {
            cap = new BigDecimal(100).subtract(target).divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).multiply(straddle.maxAllocation).setScale(8, BigDecimal.ROUND_HALF_EVEN);
        }

        BigDecimal counterBalance = exchange.getBalance(currencyPair.counter).setScale(8, BigDecimal.ROUND_HALF_EVEN).min(cap);

        BigDecimal allocationSize = exchange.getPriceIn(balance, currencyPair);

        BigDecimal allocationTotal = allocationSize.add(counterBalance);

        BigDecimal balancePercent = allocationSize.divide(allocationTotal, 8, BigDecimal.ROUND_HALF_EVEN).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN);
        if (balancePercent.compareTo(straddle.lowerMargin) >= 0) {
            return false;
        }
        if (balancePercent.add(straddle.pivotMargin).compareTo(target) < 0) {
            return true;
        }
        return false;
    }

    public boolean isTimeToSell(BigDecimal balance, BigDecimal averagePrice) {
        BigDecimal target = straddle.getTargetPercent(averagePrice).get();

        BigDecimal cap = BigDecimal.valueOf(Double.MAX_VALUE);
        if (straddle.maxAllocation.compareTo(BigDecimal.ZERO) > 0) {
            cap = new BigDecimal(100).subtract(target).divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).multiply(straddle.maxAllocation).setScale(8, BigDecimal.ROUND_HALF_EVEN);
        }
        BigDecimal counterBalance = exchange.getBalance(currencyPair.counter).setScale(8, BigDecimal.ROUND_HALF_EVEN).min(cap);

        BigDecimal allocationSize = exchange.getPriceIn(balance, currencyPair);

        BigDecimal allocationTotal = allocationSize.add(counterBalance);

        BigDecimal balancePercent = allocationSize.divide(allocationTotal, 8, BigDecimal.ROUND_HALF_EVEN).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN);

        if (balancePercent.compareTo(straddle.upperMargin) <= 0) {
            return false;
        }
        if (balancePercent.subtract(straddle.pivotMargin).compareTo(target) > 0) {
            return true;
        }
        return false;
    }

    public void report(String aString) {
        reporter.report(exchange + ": " + aString);
    }


    public void cancel() {
        running = false;
        if (order != null) {
            try {
                order.cancelOrder();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StraddleStrategy)) return false;
        StraddleStrategy that = (StraddleStrategy) o;
        return Objects.equals(straddle, that.straddle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(straddle);
    }
}
