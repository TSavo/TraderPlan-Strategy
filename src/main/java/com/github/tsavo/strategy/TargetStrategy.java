package com.github.tsavo.strategy;

import com.github.tsavo.traderplan.exchange.Exchange;
import com.github.tsavo.traderplan.exchange.Order;
import com.tsavo.trade.database.Reporter;
import com.tsavo.trade.database.model.Target;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * Created by evilg on 7/18/2017.
 */
public class TargetStrategy implements Strategy {
    public final Exchange exchange;
    public final CurrencyPair currencyPair;
    public boolean active;
    public Order position;
    public boolean running = true;
    public Reporter reporter;
    public Target target;

    public TargetStrategy(Exchange exchange, Target aTarget, Reporter reporter) {
        this.exchange = exchange;
        this.currencyPair = aTarget.currencyPair.toCurrencyPair();
        this.reporter = reporter;
        this.target = aTarget;
    }

    @Override
    public void findOpportunities() throws ExchangeException, NotAvailableFromExchangeException, NotYetImplementedForExchangeException, IOException {
        BigDecimal balance;
        BigDecimal averagePrice;
        balance = exchange.getBalance(currencyPair.base);

        List<LimitOrder> lowestAsks = exchange.getLowestAsks(currencyPair);
        if (lowestAsks.size() == 0) {
            return;
        }

        List<LimitOrder> orders = exchange.getHighestBids(currencyPair);

        if (isTimeToBuy(balance) && running) {
            report("Target strategy for " + currencyPair + " is now in ALLOCATION.");
            BigDecimal targetPrice = exchange.getAveragePrice(currencyPair).multiply(new BigDecimal(2));
            BigDecimal tradeAmount = target.establishingPosition.min(target.target.subtract(balance)).setScale(8, BigDecimal.ROUND_HALF_EVEN);

            LimitOrder order = new LimitOrder.Builder(org.knowm.xchange.dto.Order.OrderType.BID, currencyPair).tradableAmount(tradeAmount).limitPrice(targetPrice).build();
            position = exchange.createTrailStopMarketMakerLimitOrder(order, order.getLimitPrice().divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).setScale(8, BigDecimal.ROUND_HALF_EVEN));
            position.openOrder();
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

            if(balance.multiply(new BigDecimal(1.01)).add(order.getTradableAmount()).compareTo(target.target) >= 0){
                report("Target strategy has reached it's target of " + target.target + " " + currencyPair + ".");
            }

            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }else if (isTimeToSell(balance) && running) {
            report("Target strategy for " + currencyPair + " is now in DISTRIBUTION.");
            BigDecimal sellPrice = BigDecimal.ZERO;

            BigDecimal amountToSell = target.establishingPosition.min(balance.subtract(target.target)).setScale(8, BigDecimal.ROUND_HALF_EVEN);

            LimitOrder order = new LimitOrder.Builder(org.knowm.xchange.dto.Order.OrderType.ASK, currencyPair).tradableAmount(amountToSell).limitPrice(sellPrice).build();
            BigDecimal amountToTrailBy = exchange.getAveragePrice(currencyPair).divide(new BigDecimal(100), 8, BigDecimal.ROUND_HALF_EVEN).setScale(8, BigDecimal.ROUND_HALF_EVEN);
            position = exchange.createTrailStopMarketMakerLimitOrder(order, amountToTrailBy);
            position.openOrder();
            if (!position.fillOrCancel()) {
                return;
            }
            order = position.order;

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if(balance.multiply(new BigDecimal(.99)).subtract(order.getTradableAmount()).compareTo(target.target) <= 0){
                report("Target strategy has reached it's target of " + target.target + " " + currencyPair + ".");
            }

            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


    }

    public boolean isTimeToBuy(BigDecimal balance) {

        Date lastBuy = exchange.getLastBuyDate(currencyPair);
        if (lastBuy != null && lastBuy.toInstant().plusSeconds(target.delay).isAfter(new Date().toInstant())) {
            return false;
        }
        return balance.multiply(new BigDecimal(1.01)).compareTo(target.target)<0;
    }

    public boolean isTimeToSell(BigDecimal balance) {

        Date lastSell = exchange.getLastSellDate(currencyPair);
        if (lastSell != null && lastSell.toInstant().plusSeconds(target.delay).isAfter(new Date().toInstant())) {
            return false;
        }
        return balance.multiply(new BigDecimal(.99)).compareTo(target.target)>0;
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
