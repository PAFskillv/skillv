import { UrlDto } from "../types";

export default function Home() {
  const handleLogin = async () => {
    try {
      const response = await fetch("http://localhost:8080/auth/url");
      const data = (await response.json()) as UrlDto;
      window.location.href = data.url;
    } catch (error) {
      console.error("Error generating auth URL:", error);
    }
  };

  return (
    <div className="text-center">
      <button
        onClick={handleLogin}
        className="bg-blue-600 hover:bg-blue-700 text-white font-bold py-2 px-6 rounded focus:outline-none focus:shadow-outline"
      >
        Login
      </button>
    </div>
  );
}
