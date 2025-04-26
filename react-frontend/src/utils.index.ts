import { SavedToken } from "./types";

export function migrateExistingTokens(): SavedToken[] {
    try {
        const savedTokens = JSON.parse(
            localStorage.getItem("oauth2Tokens") ?? "[]"
        );

        const migratedTokens = savedTokens.map((token: Partial<SavedToken>) => {
            // Ensure all required fields exist with sensible defaults
            const validatedToken: SavedToken = {
                id: token.id ?? Date.now(),
                accessToken: token.accessToken ?? "",
                refreshToken: token.refreshToken ?? "",
                expiresIn: typeof token.expiresIn === "number" ? token.expiresIn : 3600, // Default to 1 hour
                createdAt: token.createdAt ?? new Date().toISOString(),
            };

            // Validate date format
            try {
                new Date(validatedToken.createdAt);
            } catch {
                validatedToken.createdAt = new Date().toISOString();
            }

            return validatedToken;
        });

        localStorage.setItem("oauth2Tokens", JSON.stringify(migratedTokens));
        return migratedTokens;
    } catch (error) {
        console.error("Error migrating tokens:", error);
        localStorage.setItem("oauth2Tokens", "[]");
        return [];
    }
}