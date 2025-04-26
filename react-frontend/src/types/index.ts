export interface UrlDto {
  url: string;
}

export interface TokenDto {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}

export interface SavedToken {
  id: number;
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  createdAt: string;
}

export interface RefreshStatusState {
  [key: number]: "loading" | "success" | "error" | null;
}
