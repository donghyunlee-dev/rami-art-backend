package com.rami.artstudio.security;

import java.util.UUID;

public record AuthPrincipal(UUID adminUserId, String email, String role) {
}
