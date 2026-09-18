package com.meisijiya.campusfood.module.auth;

import com.meisijiya.campusfood.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 服务:access + refresh 双 token 生成与校验。
 *
 * <p>JJWT 0.12.6 风格;签名算法 HMAC-SHA256。
 *
 * @author meisijiya
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey signingKey;

    public JwtService(JwtProperties props) {
        this.props = props;
        // 至少 256 bit,这里直接当 HMAC-SHA256 key
        this.signingKey = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 access token(subject = username / studentId)。 */
    public String generateAccessToken(UserDetails userDetails) {
        return generateToken(userDetails, props.accessTokenExpiration(), Map.of("typ", "access"));
    }

    /** 生成 refresh token。 */
    public String generateRefreshToken(UserDetails userDetails) {
        return generateToken(userDetails, props.refreshTokenExpiration(), Map.of("typ", "refresh"));
    }

    private String generateToken(UserDetails userDetails, long ttlMillis, Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put("authorities", userDetails.getAuthorities().stream()
                .map(Object::toString)
                .toList());

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuer(props.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .claims(claims)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析 subject(username / studentId)。 */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /** 校验 token 是否有效(签名 + 过期 + subject 一致)。 */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 校验 token 签名与 issuer,不依赖 UserDetails。 */
    public boolean isTokenWellFormed(String token) {
        try {
            Claims claims = parseClaims(token);
            return props.issuer().equals(claims.getIssuer())
                    && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(parseClaims(token));
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(props.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}