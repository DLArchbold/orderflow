package com.orderflow.inventory.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** PLAN step 16c: POST /admin/dlt/replay returns the number of dead letters replayed. */
@RestController
@ConditionalOnProperty(name = "orderflow.admin.enabled", havingValue = "true")
public class DeadLetterController {
    private final DeadLetterReplayer replayer;

    public DeadLetterController(DeadLetterReplayer replayer) {
        this.replayer = replayer;
    }

    @PostMapping("/admin/dlt/replay")
    public int replay() throws Exception {
        return replayer.replay();
    }
}
