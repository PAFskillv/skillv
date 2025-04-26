import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { SavedToken, TokenDto } from "../types";
import { migrateExistingTokens } from "../utils.index";

export default function Callback() {
  const [searchParams] = useSearchParams();
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    const code = searchParams.get("code");

    if (!code) {
      setError("No authorization code found in URL");
      setLoading(false);
      return;
    }

    const processCode = async () => {
      try {
        const response = await fetch(
          `http://localhost:8080/auth/callback?code=${code}`
        );

        if (!response.ok) {
          throw new Error("Failed to exchange authorization code for tokens");
        }

        const tokenData = (await response.json()) as TokenDto;

        // Save tokens to localStorage with proper validation
        const savedTokens = migrateExistingTokens();
        const newToken: SavedToken = {
          id: Date.now(),
          accessToken: tokenData.accessToken,
          refreshToken: tokenData.refreshToken,
          expiresIn: tokenData.expiresInSeconds || 3600, // Default to 1 hour if not provided
          createdAt: new Date().toISOString(),
        };
        savedTokens.push(newToken);
        localStorage.setItem("oauth2Tokens", JSON.stringify(savedTokens));

        navigate("/tokens");
      } catch (err) {
        setError(
          err instanceof Error ? err.message : "An unknown error occurred"
        );
      } finally {
        setLoading(false);
      }
    };

    processCode();
  }, [searchParams, navigate]);

  if (loading) {
    return (
      <div className="text-center py-8">
        Processing authentication... Please wait.
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded relative">
        <strong>Error:</strong> {error}
      </div>
    );
  }

  return null;
}
