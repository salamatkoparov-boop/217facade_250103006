/**
 * Facade: single entry point hiding the five airline booking subsystems.
 */
public class FlightBookingFacade {
    // Encapsulated subsystem references
    private final SeatInventorySystem seatSystem;
    private final BaggagePricingEngine baggageEngine;
    private final CurrencyConverter currencyConverter;
    private final PaymentProcessor paymentProcessor;
    private final TicketIssuer ticketIssuer;

    public FlightBookingFacade() {
        this.seatSystem = new SeatInventorySystem();
        this.baggageEngine = new BaggagePricingEngine();
        this.currencyConverter = new CurrencyConverter();
        this.paymentProcessor = new PaymentProcessor();
        this.ticketIssuer = new TicketIssuer();
    }

    /** Backward-compatible version: loyalty tier defaults to STANDARD. */
    public String bookFlight(String passengerName, String flightCode, String seatNumber,
                             double baseFareUSD, int bagCount, double bagWeightKg,
                             String currency, String cardNumber) {
        return bookFlight(passengerName, flightCode, seatNumber, baseFareUSD,
                bagCount, bagWeightKg, currency, cardNumber, "STANDARD");
    }

    /**
     * Books a complete flight itinerary: seat lock, baggage pricing, currency
     * conversion, payment, rollback on failure, and ticket issuance.
     */
    public String bookFlight(String passengerName, String flightCode, String seatNumber,
                             double baseFareUSD, int bagCount, double bagWeightKg,
                             String currency, String cardNumber, String loyaltyTier) {
        String tier = (loyaltyTier == null) ? "STANDARD" : loyaltyTier.trim().toUpperCase();
        boolean baggageWaived = tier.equals("SILVER") || tier.equals("GOLD");

        String lockToken = null;
        try {
            // 1. Connect and lock the seat
            seatSystem.connectToReservationServer();
            lockToken = seatSystem.lockSeat(flightCode, seatNumber, passengerName);

            // 2. Baggage fee (skipped entirely for SILVER / GOLD)
            double baggageFeeUSD = baggageWaived
                    ? 0.0
                    : baggageEngine.calculateBaggageFeeUSD(bagCount, bagWeightKg);
            double totalUSD = baseFareUSD + baggageFeeUSD;

            // 3. Currency conversion
            double convertedTotal = currencyConverter.convertFromUSD(totalUSD, currency);

            // 4. Payment (promo for SILVER goes after init, before charge)
            paymentProcessor.initializeGateway();
            if (tier.equals("SILVER")) {
                paymentProcessor.applyPromoCode("SILVER-FREE-BAG");
            }
            String receiptId = paymentProcessor.chargeCard(cardNumber, convertedTotal, currency);

            // 5. Ticket
            return ticketIssuer.generateTicket(passengerName, flightCode, lockToken, receiptId);
        } catch (RuntimeException ex) {
            if (lockToken != null) {
                seatSystem.releaseSeatLock(lockToken);
            }
            System.out.println("[Facade] Booking failed for " + passengerName + ": " + ex.getMessage());
            return "BOOKING_FAILED: " + ex.getMessage();
        } finally {
            seatSystem.disconnect();
        }
    }
}
