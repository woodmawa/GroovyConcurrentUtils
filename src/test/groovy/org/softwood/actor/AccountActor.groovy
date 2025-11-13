package org.softwood.actor
/**
 * Example: Account Actor (Bank Account)
 */

class AccountActor extends ScopedValueActor<Map> {

    AccountActor(String accountName, BigDecimal initialBalance = 0.0) {
        super(accountName, makeHandler(initialBalance))
    }

    /** static factory for the message handler */
    private static ScopedValueActor.MessageHandler<Map> makeHandler(BigDecimal startBalance) {
        return { message, ctx ->
            if (!ctx.has("balance")) ctx.set("balance", startBalance)
            def balance = ctx.get("balance") as BigDecimal
            def action  = message.action

            switch (action) {
                case "deposit":
                    def amount = message.amount as BigDecimal
                    balance += amount
                    ctx.set("balance", balance)
                    println "[${ctx.actorName}] Deposited $amount, new balance: $balance"
                    return [success: true, balance: balance]

                case "withdraw":
                    def amount = message.amount as BigDecimal
                    if (balance >= amount) {
                        balance -= amount
                        ctx.set("balance", balance)
                        println "[${ctx.actorName}] Withdrew $amount, new balance: $balance"
                        return [success: true, balance: balance]
                    } else {
                        println "[${ctx.actorName}] Insufficient funds: $balance < $amount"
                        return [success: false, error: "Insufficient funds", balance: balance]
                    }

                case "balance":
                    return [balance: balance]

                case "transfer":
                    def target = message.target as AccountActor
                    def amount = message.amount as BigDecimal
                    if (balance >= amount) {
                        balance -= amount
                        ctx.set("balance", balance)
                        target.send([action: "deposit", amount: amount], ctx.actorName)
                        println "[${ctx.actorName}] Transferred $amount to ${target.name}"
                        return [success: true, balance: balance]
                    } else {
                        return [success: false, error: "Insufficient funds", balance: balance]
                    }

                default:
                    throw new IllegalArgumentException("Unknown action: $action")
            }
        } as ScopedValueActor.MessageHandler
    }
}
