// VeriBlock NodeCore CLI
// Copyright 2017-2019 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package nodecore.cli.commands.rpc;

import com.google.inject.Inject;
import io.grpc.StatusRuntimeException;
import nodecore.api.grpc.VeriBlockMessages;
import nodecore.api.grpc.utilities.ByteStringAddressUtility;
import nodecore.cli.annotations.CommandParameterType;
import nodecore.cli.annotations.CommandSpec;
import nodecore.cli.annotations.CommandSpecParameter;
import nodecore.cli.commands.serialization.FormattableObject;
import nodecore.cli.commands.serialization.TransactionInfo;
import nodecore.cli.contracts.Command;
import nodecore.cli.contracts.CommandContext;
import nodecore.cli.contracts.DefaultResult;
import nodecore.cli.contracts.Result;
import nodecore.cli.utilities.CommandUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@CommandSpec(
        name = "Abandon Transactions From Address",
        form = "abandontransactionsfromaddress",
        description = "Abandons all pending transactions from a particular source address (optionally above a particular signature index), and all dependent transactions")
@CommandSpecParameter(name = "address", required = true, type = CommandParameterType.STRING)
@CommandSpecParameter(name = "index", required = false, type = CommandParameterType.LONG)
public class AbandonTransactionsFromAddressCommand implements Command {
    private static final Logger _logger = LoggerFactory.getLogger(AbandonTransactionsFromAddressCommand.class);

    @Inject
    public AbandonTransactionsFromAddressCommand() {
    }


    @Override
    public Result execute(CommandContext context) {
        Result result = new DefaultResult();

        String address = context.getParameter("address");

        Long index = context.getParameter("index");
        try {
            VeriBlockMessages.AbandonTransactionRequest request = VeriBlockMessages.AbandonTransactionRequest.newBuilder()
                    .setAddresses(
                            VeriBlockMessages.AbandonAddressTransactionsRequest.newBuilder()
                                    .addAddresses(
                                            VeriBlockMessages.AddressIndexPair.newBuilder()
                                                    .setAddress(ByteStringAddressUtility.createProperByteStringAutomatically(address))
                                                    .setStartingSignatureIndex((index == null ? 0 : index))))
                    .build();

            VeriBlockMessages.AbandonTransactionReply reply = context
                    .adminService()
                    .abandonTransactionRequest(request);
            if (!reply.getSuccess()) {
                result.fail();
            } else {
                FormattableObject<List<TransactionInfo>> temp = new FormattableObject<>(reply.getResultsList());

                List<VeriBlockMessages.TransactionUnion> abandonedTransactions = reply.getAbandonedTransactionsList();

                List<VeriBlockMessages.Transaction> rawAbandonedTransactions = new ArrayList<>();
                for (VeriBlockMessages.TransactionUnion abandonedTransaction : abandonedTransactions) {
                    if (abandonedTransaction.hasSigned()) {
                        rawAbandonedTransactions.add(abandonedTransaction.getSigned().getTransaction());
                    } else {
                        rawAbandonedTransactions.add(abandonedTransaction.getSignedMultisig().getTransaction());
                    }
                }

                temp.success = !result.didFail();
                temp.payload = rawAbandonedTransactions
                        .stream()
                        .map(TransactionInfo::new).collect(Collectors.toList());

                context.outputObject(temp);
            }
            for (VeriBlockMessages.Result r : reply.getResultsList())
                result.addMessage(r.getCode(), r.getMessage(), r.getDetails(), r.getError());
        } catch (StatusRuntimeException e) {
            CommandUtility.handleRuntimeException(result, e, _logger);
        }

        return result;
    }
}