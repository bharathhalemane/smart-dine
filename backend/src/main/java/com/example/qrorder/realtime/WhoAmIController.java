package com.example.qrorder.realtime;

import java.security.Principal;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
public class WhoAmIController {

    /** Send anything to /app/whoami, listen on /user/queue/whoami: the server tells you who it thinks you are. */
    @MessageMapping("/whoami")
    @SendToUser("/queue/whoami")
    public WhoAmI whoAmI(Principal principal) {
        return WhoAmI.of((RealtimePrincipal) principal);
    }
}