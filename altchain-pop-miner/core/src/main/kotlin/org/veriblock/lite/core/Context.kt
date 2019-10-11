// VeriBlock Blockchain Project
// Copyright 2017-2018 VeriBlock, Inc
// Copyright 2018-2019 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package org.veriblock.lite.core

import org.veriblock.lite.params.NetworkParameters
import org.veriblock.sdk.Configuration
import java.io.File

object Context {
    val dataDir = System.getenv("DATA_DIR")
        ?: Configuration.extract("dataDir")
        ?: "./"

    val networkParameters = NetworkParameters
    val directory: File = File(".")
    val filePrefix: String = "${dataDir}vbk-${networkParameters.network}"
}