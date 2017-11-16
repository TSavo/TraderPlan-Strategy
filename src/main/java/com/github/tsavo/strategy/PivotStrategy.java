package com.github.tsavo.strategy;

import com.github.tsavo.traderplan.exchange.Exchange;
import com.github.tsavo.traderplan.exchange.NoWalletEntryException;
import com.github.tsavo.traderplan.exchange.Order;
import com.tsavo.trade.database.Reporter;
import com.tsavo.trade.database.model.Pivot;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * Created by evilg on 7/18/2017.
 */
public class PivotStrategy implements Strategy {
    public final Exchange exchange;
    public final CurrencyPair currencyPair;
    public boolean active;
    public BigDecimal maximum;
    public long timeBetweenBuys;
    public long timeBetweenSells;
    public Order position;
    public Pivot pivot;
    public boolean running = true;
    public Reporter reporter;
    public Date lastReport = null;

    public PivotStrategy(Exchange exchange, Pivot aPivot, Reporter reporter) {
        this.exchange = exchange;
        this.currencyPair = aPivot.currencyPair.toCurrencyPair();
        this.reporter = reporter;
        this.pivot = aPivot;
    }

    @Override
    public void findOpportunities() throws ExchangeException, NotAvailableFromExchangeException, NotYetImplementedForExchangeException, IOException {
        BigDecimal balance;
        BigDecimal averagePrice = BigDecimal.ZERO;
        balance = exchange.getBalance(currencyPair.base);

        try {
            averagePrice = exchange.getAverageCost(currencyPair);
        } catch (NoWalletEntryException e) {
            e.printStackTrace();
        }

        List<LimitOrder> lowestAsks = exchange.getLowestAsks(currencyPair);
        if (lowestAsks.size() == 0) {
            return;
        }

        List<LimitOrder> orders = exchange.getHighestBids(currencyPair);

        if (isTimeToBuy(balance, averagePrice) && running) {
            report("Pivot strategy for " + currencyPair + " is now in ALLOCATION.");
            BigDecimal targetPrice = exchange.getLowestAsks(currencyPair).get(0).getLimitPrice().multiply(new BigDecimal(1.1));
            BigDecimal tradeAmount;
            boolean updateBuyAction = false;
            if(balance.compareTo( exchange.getAmountIn(pivot.establishingPosition, pivot.currencyPair.toCurrencyPair())) < 0){
                tradeAmount = exchange.getAmountIn(pivot.establishingPosition, pivot.currencyPair.toCurrencyPair()).multiply(new BigDecimal(1.025)).setScale(8, BigDecimal.ROUND_HALF_EVEN);
            }else {
                if (exchange.getAmountIn(balance, currencyPair).compareTo(pivot.establishingPosition) < 0) {
                    tradeAmount = exchange.getAmountIn(pivot.establishingPosition, currencyPair);
                } else {
                    updateBuyAction = true;
                    if (pivot.buyMode.equals(Pivot.OrderMode.AMOUNT)) {
                        tradeAmount = exchange.getAmountIn(pivot.allocationAmountSchedule.get(0), pivot.currencyPair.toCurrencyPair()).setScale(8, BigDecimal.ROUND_HALF_EVEN);
                    } else {
                        tradeAmount = balance.multiply(pivot.allocationMultiplierSchedule.get(0)).setScale(8, BigDecimal.ROUND_HALF_EVEN);
                    }
                }
            }

            LimitOrder order = new LimitOrder.Builder(org.knowm.xchange.dto.Order.OrderType.BID, currencyPair).tradableAmount(tradeAmount).limitPrice(targetPrice).build();
            position = exchange.createTrailStopMarketMakerLimitOrder(order, order.getLimitPrice().divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).setScale(8, BigDecimal.ROUND_HALF_EVEN));
            try {
                position.openOrder();
            } catch (Exception e) {
                report("The pivot strategy for " + currencyPair + " couldn't open the order for " + tradeAmount + "! Error from the exchange: " + e.getMessage());
                return;
            }

            if (!position.fillOrCancel()) {
                return;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            order = position.order;
            balance = exchange.getBalance(currencyPair.base);
            BigDecimal counterAmount = exchange.getBalance(currencyPair.counter);
            BigDecimal counterBalance = exchange.getBalance(currencyPair.counter).setScale(8, BigDecimal.ROUND_HALF_EVEN);

            BigDecimal allocationSize = exchange.getPriceIn(balance, currencyPair);

            BigDecimal allocationTotal = allocationSize.add(counterBalance);

            BigDecimal balancePercent = allocationSize.divide(allocationTotal, 8, BigDecimal.ROUND_HALF_EVEN).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN);
            BigDecimal counterPercent = counterBalance.divide(allocationTotal, 8, BigDecimal.ROUND_HALF_EVEN).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN);

            if(updateBuyAction) {
                exchange.performBuyAction(pivot);
            }


            report("Pivot strategy spent " + order.getLimitPrice().multiply(order.getTradableAmount()).setScale(8, BigDecimal.ROUND_HALF_EVEN) + currencyPair.counter + " adding " + order.getTradableAmount().setScale(8, RoundingMode.HALF_EVEN) + "@" + order.getLimitPrice().setScale(8, RoundingMode.HALF_EVEN) + " of " + currencyPair.base + " on " + exchange + ". We now have " + balance.setScale(8, RoundingMode.HALF_EVEN) + "@" + averagePrice.setScale(8, RoundingMode.HALF_EVEN) + " and " + counterAmount.setScale(8, BigDecimal.ROUND_HALF_EVEN) + currencyPair.counter + " remaining. Opportunity allocation is now: " + balancePercent + "%/" + counterPercent + "%.");
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }else if (isTimeToSell(balance, averagePrice) && running) {
            report("Pivot strategy for " + currencyPair + " is now in DISTRIBUTION.");
            BigDecimal sellPrice = averagePrice.multiply(new BigDecimal(0.9));

            BigDecimal amountToSell;
            if (pivot.sellMode.equals(Pivot.OrderMode.AMOUNT)) {
                amountToSell = exchange.getAmountIn(pivot.distributionAmountSchedule.get(0), currencyPair).min(balance).setScale(8, BigDecimal.ROUND_HALF_EVEN);

            } else {
                amountToSell = balance.multiply(pivot.distributionMultiplierSchedule.get(0)).min(balance).setScale(8, BigDecimal.ROUND_HALF_EVEN);
            }
            LimitOrder order = new LimitOrder.Builder(org.knowm.xchange.dto.Order.OrderType.ASK, currencyPair).tradableAmount(amountToSell).limitPrice(sellPrice).build();
            BigDecimal amountToTrailBy = order.getLimitPrice().divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).setScale(8, BigDecimal.ROUND_HALF_EVEN);
            position = exchange.createTrailStopMarketMakerLimitOrder(order, amountToTrailBy);
            //report("Starting a trailing stop for " + amountToSell + "@" + sellPrice + " trailing by " + amountToTrailBy + ". Best price right now is: " + exchange.getHighestBids(currencyPair).get(0).getLimitPrice());
            try {
                position.openOrder();
            } catch (Exception e) {
                report("The pivot strategy for " + currencyPair + " couldn't open the order for " + amountToSell + "! Error from the exchange: " + e.getMessage());
                return;
            }
            if (!position.fillOrCancel()) {
                return;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            order = position.order;

            BigDecimal newBalance = exchange.getBalance(currencyPair.base);
            BigDecimal counterBalance = exchange.getBalance(currencyPair.counter).setScale(8, BigDecimal.ROUND_HALF_EVEN);

            BigDecimal allocationSize = exchange.getPriceIn(newBalance, currencyPair);

            BigDecimal allocationTotal = allocationSize.add(counterBalance);

            BigDecimal balancePercent = allocationSize.divide(allocationTotal, 8, BigDecimal.ROUND_HALF_EVEN).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN);
            BigDecimal counterPercent = counterBalance.divide(allocationTotal, 8, BigDecimal.ROUND_HALF_EVEN).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN);

            report("Pivot strategy sold " + amountToSell.setScale(8, RoundingMode.HALF_EVEN) + "@" + order.getLimitPrice() + " of " + currencyPair.base + " on " + exchange + ". We now have " + newBalance.setScale(8, RoundingMode.HALF_EVEN) + "@" + averagePrice + " and " + counterBalance + currencyPair.counter + ". Opportunity allocation is now: " + balancePercent + "%/" + counterPercent + "%.");
            //report("Made " + order.getLimitPrice().subtract(averagePrice).multiply(amountToSell).setScale(8, BigDecimal.ROUND_HALF_EVEN) + " " + currencyPair.counter + " buying " + amountToSell + "@" + averagePrice + " and selling it at " + order.getLimitPrice().setScale(8, BigDecimal.ROUND_HALF_EVEN) + ". We now have " + newBalance + "@" + averagePrice + " and " + counterBalance + currencyPair.counter + ". Opportunity allocation is now: " + balancePercent + "%/" + counterPercent + "%.");

            exchange.performSellAction(pivot);


            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


    }

    public boolean isTimeToBuy(BigDecimal balance, BigDecimal averagePrice) {
        if (System.getenv("NOBUY") != null) {
            return false;
        }
        Date lastBuy = exchange.getLastBuyDate(currencyPair);
        if (lastBuy != null && lastBuy.toInstant().plusSeconds(timeBetweenBuys).isAfter(new Date().toInstant())) {
            return false;
        }

        BigDecimal actualPrice = exchange.getPriceIn(balance, currencyPair);
        if (actualPrice.compareTo(pivot.establishingPosition.multiply(new BigDecimal(0.9))) < 0) {
            return true;
        }
        LimitOrder order = exchange.getLowestAsks(currencyPair).get(0);

        return order.getLimitPrice().compareTo(averagePrice.multiply(BigDecimal.ONE.subtract(pivot.allocationMarginSchedule.get(0).divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN)))) < 0;// && order.getTradableAmount().compareTo(amountToBuy) >= 0;
    }

    public boolean isTimeToSell(BigDecimal balance, BigDecimal averagePrice) {

        Date lastSell = exchange.getLastSellDate(currencyPair);
        if (lastSell != null && lastSell.toInstant().plusSeconds(timeBetweenSells).isAfter(new Date().toInstant())) {
            return false;
        }

        LimitOrder order = exchange.getHighestBids(currencyPair).get(0);
        return order.getLimitPrice().compareTo(averagePrice.multiply(BigDecimal.ONE.add(pivot.distributionMarginSchedule.get(0).divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN)))) > 0;
    }


    public void report(String aString) {
        reporter.report(  exchange + ": " + aString);
    }

    public void cancel() {
        running = false;
        if (position != null) {
            try {
                position.cancelOrder();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
