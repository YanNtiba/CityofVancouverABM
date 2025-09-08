package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class CompassCardLogger implements
	PersonMoneyEventHandler,
	IterationStartsListener,
	ShutdownListener {

	private final OutputDirectoryHierarchy outputDirectoryHierarchy;
	private final TransferAudit audit;

	private BufferedWriter writer;
	private int currentIteration = 0;

	@Inject
	public CompassCardLogger(OutputDirectoryHierarchy outputDirectoryHierarchy, TransferAudit audit) {
		this.outputDirectoryHierarchy = outputDirectoryHierarchy;
		this.audit = audit;
	}

	@Override
	public void handleEvent(PersonMoneyEvent event) {
		if (!"mobi-fare".equals(event.getPurpose()) && !"mobi-refund".equals(event.getPurpose())) return;
		boolean wasTransfer = "mobi-fare".equals(event.getPurpose()) ? audit.take(event.getPersonId()) : false;
		String tripType = "mobi-fare".equals(event.getPurpose())
			? (wasTransfer ? "Transit_Transfer" : "Standard")
			: "FirstMile_Refund";
		double farePaid = -event.getAmount();

		try {
			writer.write(String.format("%d,%s,%.2f,%s,%.2f%n",
				currentIteration, event.getPersonId(), event.getTime(), tripType, farePaid));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		this.currentIteration = event.getIteration();
		if (this.currentIteration == 0) {
			try {
				String filePath = outputDirectoryHierarchy.getOutputFilename("compass_card_log.csv");
				this.writer = Files.newBufferedWriter(Paths.get(filePath));
				this.writer.write("iteration,person_id,trip_end_time,trip_type,fare_paid\n");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		if (writer != null) {
			try {
				writer.flush();
				writer.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	@Override
	public void reset(int iteration) {
		// no per-iteration state to clear here
	}
}
