# Use the latest LTS version of Node.js (Alpine for small size)
FROM node:22-alpine

# Set working directory
WORKDIR /app

# Install dependencies first to leverage Docker cache
COPY package*.json ./
RUN npm install

# Copy the rest of the app
COPY . .

# Expose Vite dev server port (as defined in vite.config.ts)
EXPOSE 3000

# Start the Vite dev server
CMD ["npm", "run", "dev"]
