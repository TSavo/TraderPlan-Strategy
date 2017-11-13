package com.github.tsavo.strategy;

import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;

import java.io.IOException;

public interface Strategy {
	public void findOpportunities() throws ExchangeException, NotAvailableFromExchangeException, NotYetImplementedForExchangeException, IOException;
	public void cancel();

}
