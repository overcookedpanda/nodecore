// VeriBlock NodeCore
// Copyright 2017-2020 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.
package nodecore.api.ucp.commands.server;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import nodecore.api.ucp.commands.DeserializationUtility;
import nodecore.api.ucp.commands.UCPCommand;

import java.lang.reflect.Type;

public class MiningUnsubscribeDeserializer implements JsonDeserializer<MiningUnsubscribe> {

    @Override
    public MiningUnsubscribe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        try {
            return new MiningUnsubscribe(DeserializationUtility.parseArguments(json, UCPCommand.Command.MINING_UNSUBSCRIBE));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse " + json + " in " + getClass().getCanonicalName() + "!");
        }
    }
}
