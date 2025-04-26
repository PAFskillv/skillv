import { useEffect, useState } from "react";
import { RefreshStatusState, SavedToken, TokenDto } from "../types";
import { migrateExistingTokens } from "../utils.index";

export default function Tokens() {
  const [tokens, setTokens] = useState<SavedToken[]>([]);
  const [refreshStatus, setRefreshStatus] = useState<RefreshStatusState>({});
  const [revokeStatus, setRevokeStatus] = useState<RefreshStatusState>({});

  useEffect(() => {
    // Migrate tokens to fix missing fields
    const migratedTokens = migrateExistingTokens();
    setTokens(migratedTokens);

    // Add a timer to update the expiry countdown
    const timer = setInterval(() => {
      // Force re-render to update countdown timer
      setTokens((current) => [...current]);
    }, 1000);

    return () => clearInterval(timer); // Cleanup on unmount
  }, []);

  const handleRefreshToken = async (id: number, refreshToken: string) => {
    setRefreshStatus((prev) => ({ ...prev, [id]: "loading" }));

    try {
      const response = await fetch(
        `http://localhost:8080/auth/refresh?refresh_token=${refreshToken}`,
        {
          method: "POST",
        }
      );

      if (!response.ok) {
        throw new Error("Failed to refresh token");
      }

      const newTokenData = (await response.json()) as TokenDto;

      // Make sure expiresInSeconds exists, default to 3600 if not
      const expiresIn = newTokenData.expiresInSeconds || 3600;

      // Update tokens in state and localStorage
      const updatedTokens = tokens.map((token) => {
        if (token.id === id) {
          return {
            ...token,
            accessToken: newTokenData.accessToken,
            expiresIn: expiresIn,
            createdAt: new Date().toISOString(),
          };
        }
        return token;
      });

      setTokens(updatedTokens);
      localStorage.setItem("oauth2Tokens", JSON.stringify(updatedTokens));
      setRefreshStatus((prev) => ({ ...prev, [id]: "success" }));

      // Clear success status after 3 seconds
      setTimeout(() => {
        setRefreshStatus((prev) => ({ ...prev, [id]: null }));
      }, 3000);
    } catch (error) {
      console.error("Error refreshing token:", error);
      setRefreshStatus((prev) => ({ ...prev, [id]: "error" }));

      // Clear error status after 3 seconds
      setTimeout(() => {
        setRefreshStatus((prev) => ({ ...prev, [id]: null }));
      }, 3000);
    }
  };

  const handleRevokeToken = async (id: number, accessToken: string) => {
    setRevokeStatus((prev) => ({ ...prev, [id]: "loading" }));

    try {
      // Google's token revocation endpoint
      const response = await fetch(
        `https://oauth2.googleapis.com/revoke?token=${accessToken}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
          },
        }
      );

      if (!response.ok) {
        throw new Error("Failed to revoke token");
      }

      // Remove token after successful revocation
      handleDeleteToken(id);

      setRevokeStatus((prev) => ({ ...prev, [id]: "success" }));

      // Clear success status after 3 seconds
      setTimeout(() => {
        setRevokeStatus((prev) => ({ ...prev, [id]: null }));
      }, 3000);
    } catch (error) {
      console.error("Error revoking token:", error);
      setRevokeStatus((prev) => ({ ...prev, [id]: "error" }));

      // Clear error status after 3 seconds
      setTimeout(() => {
        setRevokeStatus((prev) => ({ ...prev, [id]: null }));
      }, 3000);
    }
  };

  const handleDeleteToken = (id: number) => {
    const updatedTokens = tokens.filter((token) => token.id !== id);
    setTokens(updatedTokens);
    localStorage.setItem("oauth2Tokens", JSON.stringify(updatedTokens));
  };

  const formatDate = (dateString: string) => {
    try {
      return new Date(dateString).toLocaleString();
    } catch {
      return "Invalid date";
    }
  };

  const calculateExpiry = (createdAt: string, expiresIn: number) => {
    try {
      const createdDate = new Date(createdAt);

      // Check if createdAt is a valid date
      if (isNaN(createdDate.getTime())) {
        return <span className="text-red-600">Invalid date</span>;
      }

      // Ensure expiresIn is a valid number
      if (typeof expiresIn !== "number" || isNaN(expiresIn)) {
        return <span className="text-red-600">Invalid expiry time</span>;
      }

      const expiryTime = createdDate.getTime() + expiresIn * 1000;
      const now = new Date().getTime();

      if (expiryTime < now) {
        return <span className="text-red-600">Expired</span>;
      }

      const remainingSeconds = Math.floor((expiryTime - now) / 1000);
      const minutes = Math.floor(remainingSeconds / 60);
      const seconds = remainingSeconds % 60;

      return (
        <span className="text-green-600">
          {minutes}m {seconds}s remaining
        </span>
      );
    } catch (error) {
      console.error("Error calculating expiry:", error);
      return <span className="text-red-600">Error calculating expiry</span>;
    }
  };

  return (
    <div>
      <h2 className="text-xl font-semibold mb-4">Tokens</h2>

      {tokens.length === 0 ? (
        <p className="text-center py-4 text-gray-500">
          No tokens found. Please authenticate to generate tokens.
        </p>
      ) : (
        <div className="space-y-6">
          {tokens.map((token) => (
            <div key={token.id} className="border rounded-lg p-4">
              <div className="flex justify-between items-start mb-2">
                <h3 className="font-medium">
                  Token ({formatDate(token.createdAt)})
                </h3>
                <div className="space-x-2">
                  <button
                    onClick={() =>
                      handleRefreshToken(token.id, token.refreshToken)
                    }
                    disabled={refreshStatus[token.id] === "loading"}
                    className="bg-blue-600 hover:bg-blue-700 text-white text-sm py-1 px-3 rounded"
                  >
                    {refreshStatus[token.id] === "loading"
                      ? "Refreshing..."
                      : "Refresh"}
                  </button>
                  <button
                    onClick={() =>
                      handleRevokeToken(token.id, token.accessToken)
                    }
                    disabled={revokeStatus[token.id] === "loading"}
                    className="bg-yellow-600 hover:bg-yellow-700 text-white text-sm py-1 px-3 rounded"
                  >
                    {revokeStatus[token.id] === "loading"
                      ? "Revoking..."
                      : "Revoke"}
                  </button>
                  <button
                    onClick={() => handleDeleteToken(token.id)}
                    className="bg-red-600 hover:bg-red-700 text-white text-sm py-1 px-3 rounded"
                  >
                    Delete
                  </button>
                </div>
              </div>

              {refreshStatus[token.id] === "success" && (
                <div className="bg-green-100 text-green-700 p-2 rounded mb-2">
                  Token refreshed successfully!
                </div>
              )}

              {refreshStatus[token.id] === "error" && (
                <div className="bg-red-100 text-red-700 p-2 rounded mb-2">
                  Failed to refresh token. It may be expired or invalid.
                </div>
              )}

              {revokeStatus[token.id] === "success" && (
                <div className="bg-green-100 text-green-700 p-2 rounded mb-2">
                  Token revoked successfully!
                </div>
              )}

              {revokeStatus[token.id] === "error" && (
                <div className="bg-red-100 text-red-700 p-2 rounded mb-2">
                  Failed to revoke token. It may already be invalid.
                </div>
              )}

              <div className="mb-2">
                <div className="text-sm font-medium text-gray-500">Status</div>
                <div>{calculateExpiry(token.createdAt, token.expiresIn)}</div>
              </div>

              <div className="mb-2">
                <div className="text-sm font-medium text-gray-500">
                  Access Token
                </div>
                <div className="bg-gray-100 p-2 rounded overflow-x-auto">
                  <code className="text-sm break-all">{token.accessToken}</code>
                </div>
              </div>

              <div>
                <div className="text-sm font-medium text-gray-500">
                  Refresh Token
                </div>
                <div className="bg-gray-100 p-2 rounded overflow-x-auto">
                  <code className="text-sm break-all">
                    {token.refreshToken}
                  </code>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
